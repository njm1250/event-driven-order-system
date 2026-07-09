package com.ordersystem.common.events;

public final class Topics {

    public static final String ORDER_CREATED = "inventory-order-created";
    public static final String STOCK_UPDATED = "order-stock-update";
    public static final String STOCK_UPDATE_FAILED = "order-stock-update-failed";

    private Topics() {
    }
}
