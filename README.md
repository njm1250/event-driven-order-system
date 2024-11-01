# Event-Driven Order Management System

A prototype of an event-driven order management system built with **Spring Boot** and **Kafka**.

## Tech Stack
![Java](https://img.shields.io/badge/Java-17-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.10-green) ![Kafka](https://img.shields.io/badge/Kafka-2.3.0-orange) ![MySQL](https://img.shields.io/badge/MySQL-5.7-blue) ![Docker](https://img.shields.io/badge/Docker-20.10.7-blue)

## Architecture
This system is built using a **microservice architecture**. Each service operates independently and communicates asynchronously using **Kafka** events.

### Key Services
#### 1. API Gateway

- **Role**: Routes client requests to the appropriate services and manages authentication and authorization.
- **Status**: Under development.

#### 2. Order Service

- **Role**: Handles order creation, retrieval, and cancellation.
- **Description**: Upon order creation, it publishes events to Kafka for the Inventory Service and Payment Service.

#### 3. Inventory Service

- **Role**: Manages inventory operations.
- **Description**: Checks stock availability and updates stock levels based on order creation events received from the Order Service.

#### 4. Payment Service

- **Role**: Processes payments.
- **Description**: Listens for payment request events from the Order Service, processes payments, and sends the result back to the Order Service.
- **Status**: Under development.

#### 5. Shipping Service

- **Role**: Handles shipping operations.
- **Description**: Receives shipping request events from the Order Service to manage deliveries.
- **Status**: Under development.

### Transaction Management
The system implements **SAGA Pattern** using a **Choreography-based approach**. Each service listens to Kafka events and completes its part of the transaction. In case of a failure, compensating actions are triggered to maintain system consistency.

## Notes
- Planning to add an API Gateway for JWT.
- and integrate a basic frontend for login and order management.
- and payment and Shipping services to be implemented soon.
