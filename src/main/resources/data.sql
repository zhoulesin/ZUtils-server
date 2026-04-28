-- Seed developers (password is "admin123", BCrypt encoded)
INSERT INTO developers (username, email, password, role, enabled, created_at)
SELECT 'admin', 'admin@zutils.com', '$2a$10$3gB1/ITiblcFP./WpdnXReko/DLGdw7nrm155U2Cuv3c4p7kXoU7e', 'ADMIN', true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM developers WHERE username = 'admin');

INSERT INTO developers (username, email, password, role, enabled, created_at)
SELECT 'zutils-team', 'team@zutils.com', '$2a$10$3gB1/ITiblcFP./WpdnXReko/DLGdw7nrm155U2Cuv3c4p7kXoU7e', 'DEVELOPER', true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM developers WHERE username = 'zutils-team');

-- Seed sample plugins
INSERT INTO plugins (id, function_name, description, icon, category, author, min_app_version, downloads, rating, developer_id, created_at, updated_at)
SELECT 'plugin_calculator', 'calculate', '数学计算表达式求值', '', 'UTILITY', 'ZUtils Team', '1.0.0', 2340, 4.5, (SELECT id FROM developers WHERE username = 'zutils-team'), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugins WHERE id = 'plugin_calculator');

INSERT INTO plugins (id, function_name, description, icon, category, author, min_app_version, downloads, rating, developer_id, created_at, updated_at)
SELECT 'plugin_uuid', 'generateUuid', '生成 UUID 标识符', '', 'UTILITY', 'ZUtils Team', '1.0.0', 1890, 4.2, (SELECT id FROM developers WHERE username = 'zutils-team'), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugins WHERE id = 'plugin_uuid');

INSERT INTO plugins (id, function_name, description, icon, category, author, min_app_version, downloads, rating, developer_id, created_at, updated_at)
SELECT 'plugin_greet', 'greet', '发送个性化问候', '', 'UTILITY', 'ZUtils Team', '1.0.0', 1560, 4.0, (SELECT id FROM developers WHERE username = 'zutils-team'), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugins WHERE id = 'plugin_greet');

INSERT INTO plugins (id, function_name, description, icon, category, author, min_app_version, downloads, rating, developer_id, created_at, updated_at)
SELECT 'plugin_strlength', 'strLength', '计算字符串长度', '', 'UTILITY', 'ZUtils Team', '1.0.0', 980, 3.8, (SELECT id FROM developers WHERE username = 'zutils-team'), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugins WHERE id = 'plugin_strlength');

INSERT INTO plugins (id, function_name, description, icon, category, author, min_app_version, downloads, rating, developer_id, created_at, updated_at)
SELECT 'plugin_hello', 'hello', '获取个性化问候语', '', 'UTILITY', 'ZUtils Team', '1.0.0', 720, 3.5, (SELECT id FROM developers WHERE username = 'zutils-team'), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugins WHERE id = 'plugin_hello');

INSERT INTO plugin_versions (plugin_id, version, dex_url, dex_size, class_name, parameters, required_permissions, dependencies, status, published_at)
SELECT 'plugin_calculator', '1.0.0', 'http://localhost:8080/api/v1/files/plugin_calculator_v1.0.0.dex', 4592, 'com.zutils.generated.CalculatorFunction', '[{"name":"expression","description":"数学表达式","type":"STRING","required":true}]', '[]', '[]', 'APPROVED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugin_versions WHERE plugin_id = 'plugin_calculator' AND version = '1.0.0');

INSERT INTO plugin_versions (plugin_id, version, dex_url, dex_size, class_name, parameters, required_permissions, dependencies, status, published_at)
SELECT 'plugin_uuid', '1.0.0', 'http://localhost:8080/api/v1/files/plugin_uuid_v1.0.0.dex', 4912, 'com.zutils.generated.UuidFunction', '[{"name":"count","description":"生成数量","type":"NUMBER","required":false}]', '[]', '[]', 'APPROVED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugin_versions WHERE plugin_id = 'plugin_uuid' AND version = '1.0.0');

INSERT INTO plugin_versions (plugin_id, version, dex_url, dex_size, class_name, parameters, required_permissions, dependencies, status, published_at)
SELECT 'plugin_greet', '1.0.0', 'http://localhost:8080/api/v1/files/plugin_greet_v1.0.0.dex', 4104, 'com.zutils.generated.GreetFunction', '[{"name":"name","description":"名字","type":"STRING","required":false}]', '[]', '[]', 'APPROVED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugin_versions WHERE plugin_id = 'plugin_greet' AND version = '1.0.0');

INSERT INTO plugin_versions (plugin_id, version, dex_url, dex_size, class_name, parameters, required_permissions, dependencies, status, published_at)
SELECT 'plugin_strlength', '1.0.0', 'http://localhost:8080/api/v1/files/plugin_strLength_v1.0.0.dex', 4244, 'com.zutils.generated.StrLengthFunction', '[{"name":"text","description":"输入文本","type":"STRING","required":true}]', '[]', '[]', 'APPROVED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugin_versions WHERE plugin_id = 'plugin_strlength' AND version = '1.0.0');

INSERT INTO plugin_versions (plugin_id, version, dex_url, dex_size, class_name, parameters, required_permissions, dependencies, status, published_at)
SELECT 'plugin_hello', '1.0.0', 'http://localhost:8080/api/v1/files/plugin_hello_v1.0.0.dex', 4104, 'com.zutils.generated.HelloFunction', '[{"name":"name","description":"你的名字","type":"STRING","required":false}]', '[]', '[]', 'APPROVED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM plugin_versions WHERE plugin_id = 'plugin_hello' AND version = '1.0.0');
