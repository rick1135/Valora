ALTER TABLE provents
    ALTER COLUMN com_date TYPE DATE USING (com_date AT TIME ZONE 'America/Sao_Paulo')::date,
    ALTER COLUMN payment_date TYPE DATE USING (payment_date AT TIME ZONE 'America/Sao_Paulo')::date;
