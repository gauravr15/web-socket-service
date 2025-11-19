package com.odin.web_socket_service.dto;

public class UserStatusResponse {
    private boolean online;
    private String pod;

    public UserStatusResponse() {}

    public UserStatusResponse(boolean online, String pod) {
        this.online = online;
        this.pod = pod;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getPod() {
        return pod;
    }

    public void setPod(String pod) {
        this.pod = pod;
    }
}
