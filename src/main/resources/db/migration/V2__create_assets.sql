CREATE TABLE IF NOT EXISTS assets (
                                      id UUID PRIMARY KEY,
                                      ticker VARCHAR(20) NOT NULL,
                                      ticker_normalized VARCHAR(20) NOT NULL UNIQUE,
                                      name VARCHAR(255) NOT NULL,
                                      category VARCHAR(50) NOT NULL
);
