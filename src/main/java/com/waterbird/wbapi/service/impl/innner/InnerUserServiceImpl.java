package com.waterbird.wbapi.service.impl.innner;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterbird.wbapi.common.ErrorCode;
import com.waterbird.wbapi.exception.BusinessException;
import com.waterbird.wbapi.mapper.UserMapper;
import com.waterbird.wbapicommon.model.entity.User;
import com.waterbird.wbapicommon.service.InnerUserService;
import org.apache.dubbo.config.annotation.DubboService;

import javax.annotation.Resource;

@DubboService
public class InnerUserServiceImpl implements InnerUserService {
    @Resource
    private UserMapper userMapper;

    /**
     * 实现接口中的 getInvokeUser 方法，根据 accessKey 查询用户信息。
     *
     * @param accessKey
     * @return 内部用户信息，如果找不到则返回 null
     * @throws BusinessException 参数错误时抛出业务异常
     */
    @Override
    public User getInvokeUser(String accessKey) {
        //参数校验
        if (StrUtil.isBlank(accessKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        LambdaQueryWrapper<User> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(User::getAccessKey, accessKey);
        return userMapper.selectOne(lambdaQueryWrapper);
    }
}
