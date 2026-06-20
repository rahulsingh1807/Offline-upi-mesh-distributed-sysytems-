package com.demo.upimesh.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The over-the-wire format. This is what hops from phone to phone via Bluetooth.
 *
 * Intermediate phones can read the OUTER fields (packetId, ttl, createdAt) for
 * routing and dedup. They CANNOT read `ciphertext` — it's encrypted with the
 * server's public key.
 *
 * A malicious intermediate could change `packetId` or `createdAt`. That's why
 * we use the ciphertext's hash (not packetId) as the idempotency key on the
 * server. AES-GCM ensures any tampering inside the blob is detected on decrypt.
 */
public class MeshPacket {

    @NotBlank
    private String packetId; // UUID, used by intermediates for gossip dedup

    @Min(0)
    private int ttl; // Hops remaining; intermediates decrement it

    @NotNull
    private Long createdAt; // epoch millis, when sender created the packet

    @NotBlank
    private String ciphertext; // base64(RSA-encrypted AES key + AES-GCM ciphertext)

    public MeshPacket() {}

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
