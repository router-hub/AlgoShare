package com.algoshare.auth.domain.model;

public enum EventType {
    USER_REGISTERED("USER_REGISTERED"),
    LOGIN_OTP_REQUESTED("LOGIN_OTP_REQUESTED");

    private final String value;

    EventType(String value){
        this.value = value;
    }

    @Override
    public String toString(){
        return String.valueOf(value);
    }

    public static EventType fromValue(String val){
        for(EventType st : EventType.values()){
            if (st.toString().equals(val)){
                return st;
            }
        }

        return null;
    }
}
