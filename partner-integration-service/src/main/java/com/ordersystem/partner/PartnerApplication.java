package com.ordersystem.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@SpringBootApplication
@EnableScheduling
@RestController
public class PartnerApplication {
    public static void main(String[] args) { SpringApplication.run(PartnerApplication.class, args); }
    @Value("${app.mode}") String mode;
    @Value("${app.topic}") String topic;
    @Value("${spring.kafka.consumer.group-id}") String group;
    @Value("${spring.kafka.bootstrap-servers}") String bootstrap;
    @Value("${app.partner-url}") String partnerUrl;
    @Value("${app.code-version}") String codeVersion;
    @Value("${app.trace-enabled:true}") boolean traceEnabled;
    @Value("${app.workers}") int workers;
    @Value("${app.seller-concurrency}") int sellerConcurrency;
    @Value("${app.retry-budget}") int retryBudget;
    @Value("${app.retry-window-ms}") long retryWindow;
    @Value("${app.retry-delay-ms}") long retryDelay;
    @Value("${app.backlog-limit}") int backlogLimit;
    @Value("${app.retained-limit}") int retainedLimit;
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final ObjectMapper json;
    final HikariDataSource pool;
    final KafkaTemplate<String,String> producer;
    final KafkaListenerEndpointRegistry registry;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final String instance = UUID.randomUUID().toString();
    final Map<String,Deque<Task>> pending = new LinkedHashMap<>();
    final Map<String,Integer> activeSellers = new HashMap<>();
    final Map<String,Deque<Long>> retries = new HashMap<>();
    final Set<String> activeKeys = new HashSet<>();
    final Map<Integer,Long> receivedOffsets = new ConcurrentHashMap<>();
    final AtomicInteger active = new AtomicInteger();
    final AtomicLong traceCount = new AtomicLong();
    final AtomicLong generation = new AtomicLong();
    volatile ThreadPoolExecutor executor;
    static class Task {
        PartnerOrderEvent event; Acknowledgment ack; int partition; long offset;
        int attempts; long next; long epoch;
        Task(PartnerOrderEvent event, Acknowledgment ack, int partition, long offset) {
            this.event=event; this.ack=ack; this.partition=partition; this.offset=offset;
        }
    }
    public PartnerApplication(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json,
                              HikariDataSource pool, KafkaTemplate<String,String> producer,
                              KafkaListenerEndpointRegistry registry) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.json=json;
        this.pool=pool; this.producer=producer; this.registry=registry;
    }
    @Bean
    static NewTopic partnerTopic(@Value("${app.topic}") String name) { return new NewTopic(name,1,(short)1).configs(Map.of("retention.ms","86400000","retention.bytes","67108864","segment.bytes","1048576")); }
    @Bean
    static ConcurrentKafkaListenerContainerFactory<String,String> partnerFactory(ConsumerFactory<String,String> factory,
                                                                          @Value("${app.mode}") String mode,
                                                                          org.springframework.beans.factory.ObjectProvider<PartnerApplication> app) {
        var result=new ConcurrentKafkaListenerContainerFactory<String,String>();
        result.setConsumerFactory(factory);
        result.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        result.getContainerProperties().setAsyncAcks(mode.equals("async"));
        result.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(org.apache.kafka.clients.consumer.Consumer<?,?> consumer, Collection<TopicPartition> partitions) {
                var service=app.getObject();
                synchronized(service) { service.generation.incrementAndGet(); service.pending.clear(); }
                service.trace("partitions_revoked",null,"partitions",partitions.toString());
            }
            @Override
            public void onPartitionsAssigned(org.apache.kafka.clients.consumer.Consumer<?,?> consumer, Collection<TopicPartition> partitions) {
                app.getObject().trace("partitions_assigned",null,"partitions",partitions.toString());
            }
        });
        result.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(100,Long.MAX_VALUE)));
        return result;
    }
    void trace(String stage, Task task, Object... detail) {
        if (!traceEnabled) return;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("time",System.currentTimeMillis()); data.put("stage",stage); data.put("instance",instance);
        data.put("code",codeVersion); data.put("mode",mode); data.put("thread",Thread.currentThread().getName());
        if(task!=null) {
            var e=task.event;
            data.put("runId",e.runId()); data.put("sellerId",e.sellerId()); data.put("orderId",e.orderId());
            data.put("eventId",e.eventId()); data.put("sequence",e.sequence()); data.put("operation",e.operation());
            data.put("topic",topic); data.put("partition",task.partition); data.put("offset",task.offset);
            data.put("attempt",task.attempts);
        }
        for(int i=0;i<detail.length;i+=2) data.put(detail[i].toString(),detail[i+1]);
        try { System.out.println("TRACE "+json.writeValueAsString(data)); traceCount.incrementAndGet(); }
        catch(Exception e) { throw new IllegalStateException("Telemetry failed",e); }
    }
    @KafkaListener(id="partner", groupId="${spring.kafka.consumer.group-id}", topics="${app.topic}",containerFactory="partnerFactory")
    public void receive(ConsumerRecord<String,String> record, Acknowledgment ack) throws Exception {
        PartnerOrderEvent event=json.readValue(record.value(),PartnerOrderEvent.class); event.validate();
        Task task=new Task(event,ack,record.partition(),record.offset());
        task.epoch=generation.get();
        receivedOffsets.merge(record.partition(),record.offset()+1,Math::max);
        trace("received",task);
        if(mode.equals("inbox")) {
            long begin=System.nanoTime();
            tx.executeWithoutResult(status -> {
                trace("db_acquired",task,"waitMs",(System.nanoTime()-begin)/1e6);
                if(db.queryForObject("SELECT COUNT(*) FROM inbox WHERE event_id=?",Long.class,event.eventId())>0) return;
                // Single ingestion consumer is the capacity authority; workers only reduce pending count.
                if(db.queryForObject("SELECT COUNT(*) FROM inbox WHERE state<>'DONE'",Long.class)>=backlogLimit
                    || db.queryForObject("SELECT COUNT(*) FROM inbox",Long.class)>=retainedLimit) {
                    trace("backpressure",task); throw new IllegalStateException("Inbox capacity reached");
                }
                db.update("INSERT INTO inbox(event_id,run_id,seller_id,order_id,seq,payload,topic,partition_id,kafka_offset,received_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    event.eventId(),event.runId(),event.sellerId(),event.orderId(),event.sequence(),record.value(),topic,record.partition(),record.offset(),System.currentTimeMillis());
                BoundaryGate.hit("inbox_before_commit",event.eventId());
            });
            trace("inbox_commit",task);
            BoundaryGate.hit("inbox_commit",event.eventId());
            ack.acknowledge(); trace("ack_requested",task,"meaning","inbox handoff");
        } else if(mode.equals("sequential")) {
            while(true) {
                task.attempts++;
                try { perform(task); break; }
                catch(Exception e) { trace("retry_scheduled",task,"error",e.toString()); Thread.sleep(retryDelay); }
            }
            BoundaryGate.hit("business_commit",event.eventId());
            ack.acknowledge(); trace("ack_requested",task,"meaning","business completed");
        } else if(mode.equals("async")) {
            synchronized(this) { pending.computeIfAbsent(event.key(),k->new ArrayDeque<>()).add(task); }
        } else throw new IllegalStateException("Unknown mode "+mode);
    }
    boolean completed(Task task) {
        return db.queryForObject("SELECT COUNT(*) FROM partner_effect WHERE event_id=?",Long.class,task.event.eventId())>0;
    }
    int lastSequence(PartnerOrderEvent e) {
        var values=db.queryForList("SELECT seq FROM partner_order WHERE seller_id=? AND order_id=?",Integer.class,e.sellerId(),e.orderId());
        return values.isEmpty()?0:values.get(0);
    }
    void perform(Task task) throws Exception {
        var e=task.event;
        if(completed(task)) { trace("duplicate_business",task); return; }
        if(lastSequence(e)+1!=e.sequence()) throw new IllegalStateException("Waiting for predecessor");
        long start=System.nanoTime(); trace("external_start",task,"attemptId",instance+":"+e.eventId()+":"+task.attempts);
        var request=HttpRequest.newBuilder(URI.create(partnerUrl+"/orders"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("Idempotency-Key",e.eventId()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(e))).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            trace("external_result",task,"durationMs",(System.nanoTime()-start)/1e6,"status",response.statusCode());
            if(response.statusCode()!=200) throw new IllegalStateException("Partner status "+response.statusCode());
        } catch(Exception failure) {
            trace("external_error",task,"durationMs",(System.nanoTime()-start)/1e6,"error",failure.toString()); throw failure;
        }
        BoundaryGate.hit("external_success",e.eventId());
        long begin=System.nanoTime();
        tx.executeWithoutResult(status -> {
            trace("db_acquired",task,"waitMs",(System.nanoTime()-begin)/1e6);
            if(completed(task)) return;
            int last=lastSequence(e);
            if(last+1!=e.sequence()) throw new IllegalStateException("Predecessor changed");
            db.update("INSERT INTO partner_effect VALUES(?,?,?,?,?,?,?,?,?)",e.eventId(),e.runId(),e.sellerId(),e.orderId(),e.sequence(),e.operation(),e.quantity(),e.price(),System.currentTimeMillis());
            db.update("INSERT INTO partner_order VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE seq=VALUES(seq),operation=VALUES(operation),quantity=VALUES(quantity),price=VALUES(price)",e.sellerId(),e.orderId(),e.sequence(),e.operation(),e.quantity(),e.price());
            if(mode.equals("inbox")) db.update("UPDATE inbox SET state='DONE',done_at=? WHERE event_id=?",System.currentTimeMillis(),e.eventId());
            BoundaryGate.hit("business_before_commit",e.eventId());
        });
        trace("business_commit",task);
    }
    synchronized boolean allowed(Task task,long now) {
        String seller=task.event.sellerId();
        if(active.get()>=workers || activeKeys.contains(task.event.key()) || activeSellers.getOrDefault(seller,0)>=sellerConcurrency || task.next>now) return false;
        if(task.attempts>0) {
            Deque<Long> budget=retries.computeIfAbsent(seller,k->new ArrayDeque<>());
            while(!budget.isEmpty() && budget.peekFirst()<=now-retryWindow) budget.removeFirst();
            if(budget.size()>=retryBudget) return false;
            budget.addLast(now);
            trace("retry_admitted",task,"admittedAt",now,"windowMs",retryWindow,"budget",retryBudget);
        }
        active.incrementAndGet(); activeKeys.add(task.event.key()); activeSellers.merge(seller,1,Integer::sum); return true;
    }
    @Scheduled(fixedDelay=20)
    public void dispatch() {
        if(mode.equals("sequential")) return;
        try {
            if(executor==null) executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(workers));
            List<Task> candidates=new ArrayList<>();
            if(mode.equals("inbox")) {
                var rows=db.queryForList("SELECT i.* FROM inbox i LEFT JOIN partner_order o ON o.seller_id=i.seller_id AND o.order_id=i.order_id WHERE i.state='PENDING' AND i.seq=COALESCE(o.seq,0)+1 AND i.next_at<=? ORDER BY i.received_at,i.kafka_offset LIMIT ?",System.currentTimeMillis(),backlogLimit);
                for(var row:rows) {
                    var e=json.readValue((String)row.get("payload"),PartnerOrderEvent.class);
                    Task task=new Task(e,null,((Number)row.get("partition_id")).intValue(),((Number)row.get("kafka_offset")).longValue());
                    task.attempts=((Number)row.get("attempts")).intValue(); candidates.add(task);
                }
            } else synchronized(this) { for(var queue:pending.values()) if(!queue.isEmpty()) candidates.add(queue.peekFirst()); }
            for(Task task:candidates) {
                if(!allowed(task,System.currentTimeMillis())) continue;
                executor.execute(()->runTask(task));
            }
        } catch(Exception e) { trace("dispatch_error",null,"error",e.toString()); }
    }
    void runTask(Task task) {
        boolean success=false;
        try {
            task.attempts++;
            if(mode.equals("inbox")) db.update("UPDATE inbox SET attempts=? WHERE event_id=?",task.attempts,task.event.eventId());
            perform(task);
            BoundaryGate.hit(mode.equals("inbox")?"worker_commit":"business_commit",task.event.eventId());
            if(task.ack!=null && task.epoch==generation.get()) {task.ack.acknowledge(); trace("ack_requested",task,"meaning","business completed");}
            success=true;
        } catch(Exception e) {
            task.next=System.currentTimeMillis()+retryDelay;
            trace("retry_scheduled",task,"nextAt",task.next,"error",e.toString());
            if(mode.equals("inbox")) {
                try {db.update("UPDATE inbox SET next_at=? WHERE event_id=?",task.next,task.event.eventId());}
                catch(Exception error) {trace("retry_save_error",task,"error",error.toString());}
            }
        } finally {
            synchronized(this) {
                active.decrementAndGet(); activeKeys.remove(task.event.key()); activeSellers.merge(task.event.sellerId(),-1,Integer::sum);
                if(success && task.ack!=null) { var queue=pending.get(task.event.key()); if(queue!=null && queue.peekFirst()==task) {queue.removeFirst(); if(queue.isEmpty()) pending.remove(task.event.key());} }
            }
        }
    }
    @GetMapping("/observe")
    public Map<String,Object> observe() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("time",System.currentTimeMillis()); result.put("instance",instance); result.put("mode",mode);
        result.put("receivedOffsets",receivedOffsets); result.put("active",active.get()); result.put("traceCount",traceCount.get());
        synchronized(this) {result.put("sellerActive",new HashMap<>(activeSellers)); result.put("memoryPending",pending.values().stream().mapToInt(Deque::size).sum());}
        var mx=pool.getHikariPoolMXBean();
        result.put("dbActive",mx.getActiveConnections()); result.put("dbWaiting",mx.getThreadsAwaitingConnection());
        result.put("heapUsed",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        result.put("cpuNanos",ProcessHandle.current().info().totalCpuDuration().map(Duration::toNanos).orElse(0L));
        var container=registry.getListenerContainer("partner"); result.put("paused",container!=null && container.isContainerPaused());
        result.put("assigned",container==null || container.getAssignedPartitions()==null ? 0 : container.getAssignedPartitions().size());
        if(container!=null) {
            var fetch=container.metrics().values().stream().flatMap(m->m.entrySet().stream()).filter(x->x.getKey().name().equals("records-lag-max")).map(x->x.getValue().metricValue()).findFirst();
            result.put("recordsLagMax",fetch.orElse(null));
        }
        try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap))) {
            var partition=new TopicPartition(topic,0);
            var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(2,TimeUnit.SECONDS).get(partition);
            long end=admin.listOffsets(Map.of(partition,OffsetSpec.latest())).all().get(2,TimeUnit.SECONDS).get(partition).offset();
            result.put("brokerCommitted",committed==null?null:committed.offset()); result.put("logEnd",end);
            result.put("committedRemaining",committed==null?null:end-committed.offset());
        } catch(Exception e) {result.put("brokerError",e.toString()); result.put("brokerCommitted",null); result.put("committedRemaining",null);}
        return result;
    }
    @PostMapping("/load")
    public synchronized List<Map<String,Object>> load(@RequestBody List<PartnerOrderEvent> events) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        producer.partitionsFor(topic);
        if (!events.isEmpty()) {
            try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap))) {
                long end=admin.listOffsets(Map.of(new TopicPartition(topic,0),OffsetSpec.latest())).all()
                        .get(2,TimeUnit.SECONDS).get(new TopicPartition(topic,0)).offset();
                long done;
                // Input controller accounting must not consume the worker pool being faulted.
                try(var accounting=java.sql.DriverManager.getConnection(pool.getJdbcUrl(),pool.getUsername(),pool.getPassword());
                    var statement=accounting.createStatement();
                    var resultSet=statement.executeQuery("SELECT COUNT(*) FROM partner_effect")) {
                    resultSet.next(); done=resultSet.getLong(1);
                }
                if (end-done+events.size()>200) throw new IllegalStateException("Global input backlog budget (200) reached");
            }
        }
        if (events.size()>backlogLimit) throw new IllegalArgumentException("Input batch exceeds capacity");
        for(var e:events) {
            e.validate(); var sent=producer.send(topic,0,e.key(),json.writeValueAsString(e)).get(5,TimeUnit.SECONDS).getRecordMetadata();
            result.add(Map.of("eventId",e.eventId(),"partition",sent.partition(),"offset",sent.offset()));
        }
        return result;
    }
    @PostMapping("/fault/db")
    public Map<String,Object> occupy(@RequestBody Map<String,Integer> request) {
        int duration=request.getOrDefault("durationMs",2000);
        if(duration<1 || duration>15000) throw new IllegalArgumentException("duration 1..15000");
        for(int i=0;i<pool.getMaximumPoolSize();i++) {
            Thread thread=new Thread(()->{
                try(var connection=pool.getConnection()) {
                    trace("connection_occupied",null); Thread.sleep(duration);
                } catch(Exception e) {trace("fault_error",null,"error",e.toString());}
            },"db-fault"); thread.setDaemon(true); thread.start();
        }
        return Map.of("accepted",true,"durationMs",duration);
    }
}
