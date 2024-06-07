# 数据库初始化
-- 创建库
create database if not exists wbapi;

-- 切换库
use wbapi;
-- 接口信息
create table interface_info
(
    id               bigint auto_increment comment '主键'
        primary key,
    name             varchar(256)                       not null comment '接口名称',
    description      varchar(256)                       null comment '描述',
    url              varchar(512)                       not null comment '接口地址',
    requestHeader    text                               null comment '请求头',
    responseHeader   text                               null comment '响应头',
    parameterExample varchar(512)                       null comment '参数示例',
    requestParams    text                               not null comment '请求参数',
    status           int      default 0                 not null comment '接口状态（0-关闭，1-开启）',
    method           varchar(256)                       not null comment '请求类型',
    userId           bigint                             not null comment '创建人',
    createTime       datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint  default 0                 not null comment '是否删除(0-未删, 1-已删)',
    sdk              varchar(512)                       null comment 'sdk'
)
    comment '接口信息';

-- auto-generated definition
create table user
(
    id           bigint auto_increment comment 'id'
        primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    unionId      varchar(256)                           null comment '微信开放平台id',
    mpOpenId     varchar(256)                           null comment '公众号openId',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin/ban',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    AccessKey    varchar(512)                           not null comment '用户调用标识',
    SecretKey    varchar(512)                           not null comment '密钥'
)
    comment '用户' collate = utf8mb4_unicode_ci;

create index idx_unionId
    on user (unionId);

-- 用户调用接口关系表
create table if not exists wbapi.`user_interface_info`
(
    `id`              bigint                             not null auto_increment comment '主键' primary key,
    `userId`          bigint                             not null comment '调用用户 id',
    `interfaceInfoId` bigint                             not null comment '接口 id',
    `totalNum`        int      default 0                 not null comment '总调用次数',
    `leftNum`         int      default 0                 not null comment '剩余调用次数',
    `status`          int      default 0                 not null comment '0-正常，1-禁用',
    `createTime`      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    `updateTime`      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    `isDelete`        tinyint  default 0                 not null comment '是否删除(0-未删, 1-已删)'
) comment '用户调用接口关系';


-- 接口收费
create table if not exists wbapi.`interface_charging`
(
    `id`             bigint                             not null auto_increment comment '主键' primary key,
    `interfaceId`    bigint                             not null comment '接口id',
    `userId`         bigint                             not null comment '用户id',
    `charging`       float                              not null comment '价格',
    `avaliableCalls` varchar(256)                       not null comment '可调用次数',
    `createTime`     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    `updateTime`     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    `isDeleted`      tinyint  default 0                 not null comment '是否删除(0-未删, 1-已删)'
) comment '接口收费';

