-- Normalize user role enum values from CamelCase to SCREAMING_SNAKE_CASE
-- to align with standard Java enum naming conventions.
UPDATE users SET role = 'USER'  WHERE role = 'User';
UPDATE users SET role = 'ADMIN' WHERE role = 'Admin';
