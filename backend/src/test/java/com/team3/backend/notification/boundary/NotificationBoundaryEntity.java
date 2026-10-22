package com.team3.backend.notification.boundary;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_boundary_entities")
public class NotificationBoundaryEntity {

    @Id
    private Long id;

    protected NotificationBoundaryEntity() {
    }
}
