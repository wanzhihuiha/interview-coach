-- 默认演示用户，仅用于本地开发/联调
MERGE INTO sys_user (id, create_time, email, openid, password, phone, roles, status, update_time, username)
KEY (username)
VALUES (1, CURRENT_TIMESTAMP, NULL, NULL, '$2a$10$yFompRdQ3LG2N8lae8/P9.yW.SokthBfFxqgGifdyRrB3XfPznV1e', NULL, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, 'demo');

MERGE INTO sys_user (id, create_time, email, openid, password, phone, roles, status, update_time, username)
KEY (username)
VALUES (2, CURRENT_TIMESTAMP, NULL, NULL, '$2b$10$l/3uf5TRNEgVs4Db3X7CGuFzztm0qcFQ.KCA35l.QjIPTmXIREnNK', NULL, 'USER,ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, 'admin');

MERGE INTO sys_user_profile (id, avatar, bio, birthday, create_time, gender, nickname, update_time, user_id)
KEY (user_id)
VALUES (1, NULL, NULL, NULL, CURRENT_TIMESTAMP, 'UNKNOWN', '张三', CURRENT_TIMESTAMP, 1);

MERGE INTO sys_user_profile (id, avatar, bio, birthday, create_time, gender, nickname, update_time, user_id)
KEY (user_id)
VALUES (2, NULL, NULL, NULL, CURRENT_TIMESTAMP, 'UNKNOWN', '管理员', CURRENT_TIMESTAMP, 2);
