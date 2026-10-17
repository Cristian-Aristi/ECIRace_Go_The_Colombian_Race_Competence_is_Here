package edu.eci.arsw.eciracego.dto;

public class CreateRoomRequest {
    private String hostName;
    private String preferredCode;

    public CreateRoomRequest() {}

    public CreateRoomRequest(String hostName, String preferredCode) {
        this.hostName = hostName;
        this.preferredCode = preferredCode;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getPreferredCode() {
        return preferredCode;
    }

    public void setPreferredCode(String preferredCode) {
        this.preferredCode = preferredCode;
    }
}