-- Seed developers (password is "admin123", BCrypt encoded)
-- Runs every startup with WHERE NOT EXISTS to avoid duplicates
INSERT INTO developers (username, email, password, role, enabled, created_at)
SELECT 'admin', 'admin@zutils.com', '$2a$10$3gB1/ITiblcFP./WpdnXReko/DLGdw7nrm155U2Cuv3c4p7kXoU7e', 'ADMIN', true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM developers WHERE username = 'admin');

INSERT INTO developers (username, email, password, role, enabled, created_at)
SELECT 'zutils-team', 'team@zutils.com', '$2a$10$3gB1/ITiblcFP./WpdnXReko/DLGdw7nrm155U2Cuv3c4p7kXoU7e', 'DEVELOPER', true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM developers WHERE username = 'zutils-team');
