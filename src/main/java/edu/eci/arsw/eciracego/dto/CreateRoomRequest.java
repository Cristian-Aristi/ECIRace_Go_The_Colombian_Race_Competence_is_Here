package edu.eci.arsw.eciracego.dto;

/** Cuerpo de POST /api/rooms/create. El host lo define la sesión, no el cliente. */
public class CreateRoomRequest {
    private String preferredCode;

    public CreateRoomRequest() {}

    public CreateRoomRequest(String preferredCode) {
        this.preferredCode = preferredCode;
    }

    public String getPreferredCode() {
        return preferredCode;
    }

    public void setPreferredCode(String preferredCode) {
        this.preferredCode = preferredCode;
    }
}
