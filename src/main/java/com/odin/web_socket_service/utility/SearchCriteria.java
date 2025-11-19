package com.odin.web_socket_service.utility;

public class SearchCriteria {
    private String key;
    private String operation;
    private Object value;
    private String condition; // Can be "AND", "OR"

    public SearchCriteria(String key, String operation, Object value, String condition) {
        this.key = key;
        this.operation = operation;
        this.value = value;
        this.condition = condition;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public boolean isValid() {
        return value != null;  // We are allowing empty strings, but not null values.
    }

}
