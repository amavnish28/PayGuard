# PayGuard

## Real-Time Transaction Fraud Detection

PayGuard is a final-year B.Tech Computer Science project for real-time financial transaction fraud detection using a hybrid approach that combines rule-based detection with machine learning.

The system is designed using a microservice-oriented architecture with Spring Boot, FastAPI, PostgreSQL, React, and machine learning models.

## Project Objectives

- Detect suspicious financial transactions in real time.
- Combine deterministic fraud rules with machine learning predictions.
- Generate APPROVE, REVIEW, or BLOCK decisions.
- Provide explainable fraud alerts using SHAP.
- Provide an analyst dashboard for reviewing suspicious transactions.
- Maintain audit logs and analyst verdicts.
- Evaluate rules, machine learning, and hybrid approaches using fraud-detection metrics.
- Provide a transaction simulator for testing the system.

## Architecture

The planned system consists of:

- Spring Boot Backend - authentication, transaction APIs, business logic, rules engine, and orchestration.
- FastAPI ML Service - machine learning model training and fraud prediction.
- PostgreSQL - transactional data, behavioral features, alerts, analyst verdicts, audit logs, and model metadata.
- React Frontend - dashboard, alerts, transaction details, and analyst workflows.
- Transaction Simulator - generates or replays transactions for testing.
- Docker Compose - local infrastructure and service orchestration.

## Technology Stack

### Backend
- Java
- Spring Boot
- Spring Security
- JWT
- Spring Data JPA
- Maven

### Machine Learning
- Python
- FastAPI
- scikit-learn
- XGBoost
- imbalanced-learn
- SHAP

### Database
- PostgreSQL

### Frontend
- React
- JavaScript / TypeScript

### Infrastructure
- Docker
- Docker Compose

## Fraud Detection Approach

PayGuard uses two complementary detection mechanisms:

### Rule-Based Detection

Examples include:

- High transaction velocity
- Amount significantly above historical behavior
- New device detection
- New location detection
- Unusual transaction hours

### Machine Learning

Candidate models include:

- Logistic Regression
- Random Forest
- XGBoost

The models will be evaluated using appropriate fraud-detection metrics and imbalance-handling techniques.

### Hybrid Decision

The rule engine and machine-learning prediction are combined to produce a final transaction decision:

- APPROVE
- REVIEW
- BLOCK

## Database

The PostgreSQL database currently contains the following core tables:

- users
- transactions
- transaction_features
- alerts
- analyst_verdicts
- audit_logs
- model_versions

The database foundation has been implemented and integrity-tested.

## Current Development Status

- [x] Development environment setup
- [x] PostgreSQL Docker container
- [x] Initial database schema
- [x] Database indexes
- [x] Database integrity testing
- [x] Initial Git repository
- [x] Spring Boot backend
- [x] JWT authentication
- [x] Transaction API
- [x] Rule engine
- [x] Machine learning pipeline
- [x] FastAPI ML service
- [x] Hybrid fraud decision engine
- [ ] React dashboard
- [ ] Transaction simulator
- [ ] Load testing
- [ ] Dockerized application
- [ ] Final documentation

## Project Structure

```text
PayGuard/
├── backend/
├── database/
│   └── init.sql
├── frontend/
├── ml-service/
├── simulator/
├── .gitignore
├── docker-compose.yml
└── README.md
```
