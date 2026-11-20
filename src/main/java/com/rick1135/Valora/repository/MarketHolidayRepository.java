package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.MarketHoliday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MarketHolidayRepository extends JpaRepository<MarketHoliday, LocalDate> {

    boolean existsByHolidayDate(LocalDate holidayDate);

    List<MarketHoliday> findByHolidayDateBetween(LocalDate start, LocalDate end);
}
