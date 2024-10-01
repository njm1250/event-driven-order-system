package com.ordersystem.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name="inventories")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inventory_id")
    private Long inventoryId;

    @Column(name = "product_cd")
    private String productCode;

    @Column(name = "stock_quantity")
    private int stockQuantity;

    @Version
    private Integer version;

    @Builder
    public Inventory(String productCode, int stockQuantity) {
        this.productCode = productCode;
        this.stockQuantity = stockQuantity;
    }
}
