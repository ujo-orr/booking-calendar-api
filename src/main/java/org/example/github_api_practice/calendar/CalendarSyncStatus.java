package org.example.github_api_practice.calendar;

public enum CalendarSyncStatus {
    NOT_CONNECTED,
    PENDING_CREATE,
    CREATING,
    SYNCED,
    CREATE_FAILED,
    PENDING_DELETE,
    DELETING,
    DELETED,
    DELETE_FAILED
}
