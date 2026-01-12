package com.algoshare.auth.domain.model;

public enum TokenType {
    PRE_AUTH("PRE_AUTH"),
    ACCESS("ACCESS");

    private final String value;

    TokenType(String val){
        this.value = val;
    }

    @Override
    public String toString(){
        return this.value;
    }

    public static TokenType fromValue(String val){
        for (TokenType tokenType : TokenType.values()){
            if (tokenType.toString().equals(val)){
                return tokenType;
            }
        }
        return null;
    }
}
