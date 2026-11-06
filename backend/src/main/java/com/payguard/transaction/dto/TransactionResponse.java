package com.payguard.transaction.dto;

public class TransactionResponse {

    private String transactionId;
    private String decision;
    private String message;

    public TransactionResponse() {
    }

    public TransactionResponse(String transactionId, String decision, String message) {
        this.transactionId = transactionId;
        this.decision = decision;
        this.message = message;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
