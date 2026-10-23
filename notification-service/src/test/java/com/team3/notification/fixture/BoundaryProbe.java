package com.team3.notification.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only persistence fixture; no Notification business model is introduced yet. */
@Entity
@Table(name = "boundary_probe")
public class BoundaryProbe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public BoundaryProbe() {
    }
}
