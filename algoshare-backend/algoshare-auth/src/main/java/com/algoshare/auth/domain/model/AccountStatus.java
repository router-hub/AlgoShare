package com.algoshare.auth.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AccountStatus {
    PENDING_EMAIL_VERIFICATION("PENDING_EMAIL_VERIFICATION"),
    PENDING_MFA("PENDING_MFA"),
    ACTIVE("ACTIVE"),
    LOCKED("LOCKED"),
    SUSPENDED("SUSPENDED"),
    DELETED("DELETED");

    private final String value;
    AccountStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String toString(){
        return String.valueOf(value);
    }

    @JsonCreator
    public static AccountStatus fromValue(String st){
        for (AccountStatus a : AccountStatus.values()){
            if ( String.valueOf(a.value).equals(st)){
                return a;
            }
        }
        return null;
    }
}

