/*
 * 初始化数据库账号（全新环境执行一次，需要管理员权限）。
 *
 * 用途：让「克隆代码 → 启动」可复现。应用启动只依赖一个对 survey_db 有 readWrite 权限的账号，
 * 不依赖管理员权限；这个账号需要在首次部署时创建。
 *
 * 用法（用安装 MongoDB 时创建的管理员账号执行）：
 *   mongosh "mongodb://<admin>:<pwd>@localhost:27017/admin?authSource=admin" db/init-db.js
 *
 * 执行前请修改下面的账号与密码，并同步到启动参数或 application.yml 的 MONGODB_URI。
 */

const APP_DB = "survey_db";
const APP_USER = "survey_app";
const APP_PASSWORD = "survey_app_pwd"; // 首次部署请改成强密码
const APP_READ_WRITE_ONLY = true; // true = 只给 readWrite（推荐）；false = 追加 dbAdmin 以便执行 schema 脚本

const target = db.getSiblingDB(APP_DB);
const roles = [{ role: "readWrite", db: APP_DB }];
if (!APP_READ_WRITE_ONLY) {
  roles.push({ role: "dbAdmin", db: APP_DB });
}

const existing = target.getUser(APP_USER);
if (existing) {
  target.updateUser(APP_USER, { pwd: APP_PASSWORD, roles: roles });
  print(`已更新账号 ${APP_USER}，角色：${roles.map(r => r.role).join(", ")}`);
} else {
  target.createUser({ user: APP_USER, pwd: APP_PASSWORD, roles: roles });
  print(`已创建账号 ${APP_USER}，角色：${roles.map(r => r.role).join(", ")}`);
}

print("");
print("下一步：");
print(`  1. 启动应用（会自动创建集合与索引）`);
print(`  2. 如需集合级校验器，执行 db/apply-validators.js（需要 dbAdmin）`);
print(`  3. 连接串示例：mongodb://${APP_USER}:<pwd>@localhost:27017/${APP_DB}?authSource=${APP_DB}`);
