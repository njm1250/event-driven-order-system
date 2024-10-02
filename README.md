# Event-Driven Order Management System

A prototype of an event-driven order management system built with **Spring Boot** and **Kafka**.

## Tech Stack
![Java](https://img.shields.io/badge/Java-17-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.10-green) ![Kafka](https://img.shields.io/badge/Kafka-2.3.0-orange) ![MySQL](https://img.shields.io/badge/MySQL-5.7-blue) ![Docker](https://img.shields.io/badge/Docker-20.10.7-blue)

## Architecture
This system is built using a **microservice architecture**. Each service operates independently and communicates asynchronously using **Kafka** events.

### Key Services
- **API Gateway**: 개발중
- **Order Service**: 설명추가
- **Inventory Service**: 설명추가
- **Payment and Shipping Services**: 개발중

### Transaction Management
The system implements **SAGA Pattern** using a **Choreography-based approach**. Each service listens to Kafka events and completes its part of the transaction. In case of a failure, compensating actions are triggered to maintain system consistency.

### Notes
- Planning to add an API Gateway for JWT.
- and integrate a basic frontend for login and order management.
- and payment and Shipping services to be implemented soon.
