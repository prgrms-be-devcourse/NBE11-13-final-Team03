package com.team3.notification.domain.repository;

import com.team3.notification.domain.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> { }
