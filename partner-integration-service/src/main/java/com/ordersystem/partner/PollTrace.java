package com.ordersystem.partner;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.util.Map;
import java.util.UUID;

/** Raw poll boundaries establish which records asyncAcks is waiting for. */
public class PollTrace implements ConsumerInterceptor<String,String> {
    @Override public ConsumerRecords<String,String> onConsume(ConsumerRecords<String,String> records) {
        if (!records.isEmpty() && !"false".equalsIgnoreCase(System.getenv("APP_TRACE_ENABLED"))) {
            String poll=UUID.randomUUID().toString();
            for (var record:records) System.out.println("POLL {\"time\":"+System.currentTimeMillis()
                    +",\"pollId\":\""+poll+"\",\"partition\":"+record.partition()+",\"offset\":"+record.offset()
                    +",\"batchSize\":"+records.count()+"}");
        }
        return records;
    }
    @Override public void onCommit(Map<TopicPartition,OffsetAndMetadata> offsets) { }
    @Override public void close() { }
    @Override public void configure(Map<String,?> configs) { }
}
