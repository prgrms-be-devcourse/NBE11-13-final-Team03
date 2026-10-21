package com.team3.backend.preference.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class UserOpportunityPreferenceTest {

    @Test
    void savingAndUnsavingUpdatesOnlySavedState() {
        LocalDateTime createdAt = LocalDateTime.of(2020, 1, 1, 0, 0);
        UserOpportunityPreference preference = UserOpportunityPreference.builder()
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();

        preference.markSaved();

        assertTrue(preference.isSaved());
        assertFalse(preference.isHidden());
        assertNotNull(preference.getSavedAt());
        assertNull(preference.getHiddenAt());
        assertEquals(preference.getSavedAt(), preference.getUpdatedAt());
        assertEquals(createdAt, preference.getCreatedAt());
        assertFalse(preference.isInactive());

        LocalDateTime savedAt = preference.getSavedAt();
        LocalDateTime updatedAt = preference.getUpdatedAt();
        preference.markSaved();
        assertEquals(savedAt, preference.getSavedAt());
        assertEquals(updatedAt, preference.getUpdatedAt());

        preference.clearSaved();
        assertFalse(preference.isSaved());
        assertNull(preference.getSavedAt());
        assertTrue(preference.getUpdatedAt().isAfter(updatedAt));
        assertTrue(preference.isInactive());
        assertEquals(createdAt, preference.getCreatedAt());

        updatedAt = preference.getUpdatedAt();
        preference.clearSaved();
        assertEquals(updatedAt, preference.getUpdatedAt());
    }

    @Test
    void hidingAndUnhidingUpdatesOnlyHiddenState() {
        UserOpportunityPreference preference =
                UserOpportunityPreference.builder().build();

        preference.markHidden();

        assertTrue(preference.isHidden());
        assertFalse(preference.isSaved());
        assertNotNull(preference.getHiddenAt());
        assertNull(preference.getSavedAt());
        assertEquals(preference.getHiddenAt(), preference.getUpdatedAt());
        assertFalse(preference.isInactive());

        LocalDateTime hiddenAt = preference.getHiddenAt();
        LocalDateTime updatedAt = preference.getUpdatedAt();
        preference.markHidden();
        assertEquals(hiddenAt, preference.getHiddenAt());
        assertEquals(updatedAt, preference.getUpdatedAt());

        preference.clearHidden();
        assertFalse(preference.isHidden());
        assertNull(preference.getHiddenAt());
        assertTrue(preference.getUpdatedAt().isAfter(updatedAt));
        assertTrue(preference.isInactive());

        updatedAt = preference.getUpdatedAt();
        preference.clearHidden();
        assertEquals(updatedAt, preference.getUpdatedAt());
    }

    @Test
    void changingOneStatePreservesTheOther() {
        UserOpportunityPreference preference =
                UserOpportunityPreference.builder().build();

        preference.markSaved();
        LocalDateTime savedAt = preference.getSavedAt();
        preference.markHidden();
        LocalDateTime hiddenAt = preference.getHiddenAt();

        assertTrue(preference.isSaved());
        assertTrue(preference.isHidden());
        assertEquals(savedAt, preference.getSavedAt());
        assertEquals(hiddenAt, preference.getHiddenAt());
        assertFalse(preference.isInactive());

        preference.clearSaved();
        assertTrue(preference.isHidden());
        assertEquals(hiddenAt, preference.getHiddenAt());
        assertFalse(preference.isInactive());

        preference.markSaved();
        LocalDateTime newSavedAt = preference.getSavedAt();
        preference.clearHidden();
        assertTrue(preference.isSaved());
        assertEquals(newSavedAt, preference.getSavedAt());
        assertFalse(preference.isInactive());

        preference.clearSaved();
        assertTrue(preference.isInactive());
    }
}
