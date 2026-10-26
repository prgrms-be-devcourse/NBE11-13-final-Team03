package com.team3.notification.message;

/** Application-defined notification types supported by the shared wire contracts. */
public enum NotificationType {
    APPLICATION_OPENED, APPLICATION_CLOSED, DEADLINE_SOON, DEADLINE_EXTENDED,
    BECAME_ELIGIBLE, ELIGIBILITY_DOWNGRADED, ELIGIBILITY_REVIEW_REQUIRED, NEW_MATCH
}
