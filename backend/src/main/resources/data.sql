-- H2 默认演示数据，仅用于本地开发/联调；不属于 MySQL 迁移。
MERGE INTO sys_user (id, create_time, email, openid, password, phone, roles, status, update_time, username)
KEY (username)
VALUES (1, CURRENT_TIMESTAMP, NULL, NULL, '$2a$10$yFompRdQ3LG2N8lae8/P9.yW.SokthBfFxqgGifdyRrB3XfPznV1e', NULL, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, 'demo');

MERGE INTO sys_user_profile (id, avatar, bio, birthday, create_time, gender, nickname, update_time, user_id)
KEY (user_id)
VALUES (1, NULL, NULL, NULL, CURRENT_TIMESTAMP, 'UNKNOWN', '张三', CURRENT_TIMESTAMP, 1);
