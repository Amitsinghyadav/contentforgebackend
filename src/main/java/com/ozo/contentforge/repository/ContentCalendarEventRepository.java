package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ContentCalendarEventRepository extends JpaRepository<ContentCalendarEventEntity, Long> {
    List<ContentCalendarEventEntity> findByEventDate(LocalDate eventDate);
    List<ContentCalendarEventEntity> findByEventDateBetweenOrderByEventDateAsc(LocalDate startDate, LocalDate endDate);

    @Query("SELECT e FROM ContentCalendarEventEntity e WHERE e.eventDate = :date OR (e.recurrenceRule = com.ozo.contentforge.model.enums.RecurrenceRule.ANNUAL AND EXTRACT(MONTH FROM e.eventDate) = :month AND EXTRACT(DAY FROM e.eventDate) = :day)")
    List<ContentCalendarEventEntity> findActiveEventsForDate(@Param("date") LocalDate date, @Param("month") int month, @Param("day") int day);
}
