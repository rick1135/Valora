CREATE TABLE market_holidays (
    holiday_date DATE PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    scope VARCHAR(30) NOT NULL DEFAULT 'B3_NATIONAL'
);

INSERT INTO market_holidays (holiday_date, name)
SELECT make_date(year_number, month_number, day_number), name
FROM generate_series(2020, 2030) AS years(year_number)
CROSS JOIN (VALUES
    (1, 1, 'Confraternização Universal', 2020),
    (4, 21, 'Tiradentes', 2020),
    (5, 1, 'Dia do Trabalho', 2020),
    (9, 7, 'Independência', 2020),
    (10, 12, 'Nossa Senhora Aparecida', 2020),
    (11, 2, 'Finados', 2020),
    (11, 15, 'Proclamação da República', 2020),
    (11, 20, 'Consciência Negra', 2024),
    (12, 25, 'Natal', 2020)
) AS holidays(month_number, day_number, name, first_year)
WHERE year_number >= first_year
ON CONFLICT (holiday_date) DO NOTHING;

INSERT INTO market_holidays (holiday_date, name)
SELECT easter_date + offset_days, name
FROM (VALUES
    (DATE '2020-04-12'),
    (DATE '2021-04-04'),
    (DATE '2022-04-17'),
    (DATE '2023-04-09'),
    (DATE '2024-03-31'),
    (DATE '2025-04-20'),
    (DATE '2026-04-05'),
    (DATE '2027-03-28'),
    (DATE '2028-04-16'),
    (DATE '2029-04-01'),
    (DATE '2030-04-21')
) AS easter(easter_date)
CROSS JOIN (VALUES
    (-48, 'Segunda-feira de Carnaval'),
    (-47, 'Terça-feira de Carnaval'),
    (-2, 'Sexta-feira Santa'),
    (60, 'Corpus Christi')
) AS holidays(offset_days, name)
ON CONFLICT (holiday_date) DO NOTHING;

INSERT INTO benchmarks (id, name, ticker, type, provider)
VALUES (gen_random_uuid(), 'SELIC', 'SELIC', 'INTEREST_RATE', 'BCB')
ON CONFLICT (ticker) DO NOTHING;
