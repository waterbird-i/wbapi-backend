package com.waterbird.wbapi.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.waterbird.wbapi.common.ErrorCode;
import com.waterbird.wbapi.constant.CommonConstant;
import com.waterbird.wbapi.exception.BusinessException;
import com.waterbird.wbapi.exception.ThrowUtils;
import com.waterbird.wbapi.model.dto.user.UserQueryRequest;
import com.waterbird.wbapi.model.dto.user.UserUpdateRequest;
import com.waterbird.wbapi.model.enums.UserRoleEnum;
import com.waterbird.wbapi.model.vo.LoginUserVO;
import com.waterbird.wbapi.model.vo.UserDevKeyVO;
import com.waterbird.wbapi.model.vo.UserVO;
import com.waterbird.wbapi.utils.FileUploadUtil;
import com.waterbird.wbapi.utils.SqlUtils;
import com.waterbird.wbapicommon.common.JwtUtils;
import com.waterbird.wbapicommon.model.entity.User;
import com.waterbird.wbapi.service.UserService;

import com.waterbird.wbapi.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.waterbird.wbapi.constant.UserConstant.ADMIN_ROLE;
import static com.waterbird.wbapi.constant.UserConstant.USER_LOGIN_STATE;
import static com.waterbird.wbapicommon.constant.RedisConstant.LOGINCODEPRE;

/**
 * @author lcccccccc
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2023-12-10 23:16:18
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {
    @Resource
    private UserMapper userMapper;

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "waterbird";

    @Resource
    private Gson gson;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //登录和注册的标识，方便切换不同的令牌桶来限制验证码发送
    private static final String LOGIN_SIGN = "login";

    private static final String REGISTER_SIGN="register";

    public static final String USER_LOGIN_EMAIL_CODE ="user:login:email:code:";
    public static final String USER_REGISTER_EMAIL_CODE ="user:register:email:code:";

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        synchronized (userAccount.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = userMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
            }
            // 2. 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
            // 3. 分配 accessKey, secretKey
            String accessKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(5));
            String secretKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(8));
            // 4. 插入数据
            User user = new User();
            user.setUserAccount(userAccount);
            user.setUserPassword(encryptPassword);
            user.setAccessKey(accessKey);
            user.setSecretKey(secretKey);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request, HttpServletResponse response) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = userMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 3. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        return setLoginUser(response, user);
    }
    /**
     * 记录用户的登录态，并返回脱敏后的登录用户
     * @param response
     * @param user
     * @return
     */
    private LoginUserVO setLoginUser(HttpServletResponse response, User user) {
        String token = JwtUtils.getJwtToken(user.getId(), user.getUserName());
        Cookie cookie = new Cookie("token", token);
        // 表示这个Cookie在整个应用上下文中都有效。
        cookie.setPath("/");
        response.addCookie(cookie);
        // 保存用户状态到Redis：
        String userJson = gson.toJson(user);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + user.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return this.getLoginUserVO(user);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Long userId = JwtUtils.getUserIdByToken(request);
        if (userId == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        String userJson = stringRedisTemplate.opsForValue().get(USER_LOGIN_STATE+userId);
        User user = gson.fromJson(userJson, User.class);
        if (user == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
//        // 先判断是否已登录
//        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
//        User currentUser = (User) userObj;
//        if (currentUser == null || currentUser.getId() == null) {
//            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
//        }
//        // 从数据库查询（追求性能的话可以注释，直接走缓存）
//        long userId = currentUser.getId();
//        currentUser = this.getById(userId);
//        if (currentUser == null) {
//            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
//        }
//        return currentUser;
    }


    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
        // 仅管理员可查询
//        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
//        User user = (User) userObj;
//        return user != null && ADMIN_ROLE.equals(user.getUserRole());
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request,HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals("token")){
                Long userId = JwtUtils.getUserIdByToken(request);
                stringRedisTemplate.delete(USER_LOGIN_STATE+userId);
                Cookie timeOutCookie = new Cookie(cookie.getName(),cookie.getValue());
                timeOutCookie.setMaxAge(0);
                response.addCookie(timeOutCookie);
                return true;
            }
        }

        throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
    }
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVO(List<User> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String unionId = userQueryRequest.getUnionId();
        String mpOpenId = userQueryRequest.getMpOpenId();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(id != null, "id", id);
        queryWrapper.eq(StringUtils.isNotBlank(unionId), "unionId", unionId);
        queryWrapper.eq(StringUtils.isNotBlank(mpOpenId), "mpOpenId", mpOpenId);
        queryWrapper.eq(StringUtils.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StringUtils.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * todo 发送邮箱验证码
     * @param emailNum
     * @param captchaType
     */
    @Override
    public void sendCode(String emailNum, String captchaType) {
    }

    /**
     * todo 获取验证码
     * @param request
     * @param response
     */
    @Override
    public void getCaptcha(HttpServletRequest request, HttpServletResponse response) {

    }

    /**
     * 重置登录用户的ak，sk
     * @param request
     * @return
     */
    @Override
    public UserDevKeyVO genkey(HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if(loginUser == null){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        UserDevKeyVO userDevKeyVO = genKey(loginUser.getUserAccount());
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("userAccount",loginUser.getUserAccount());
        updateWrapper.eq("id",loginUser.getId());
        updateWrapper.set("accessKey",userDevKeyVO.getAccessKey());
        updateWrapper.set("secretKey",userDevKeyVO.getSecretKey());
        this.update(updateWrapper);
        loginUser.setAccessKey(userDevKeyVO.getAccessKey());
        loginUser.setSecretKey(userDevKeyVO.getSecretKey());

        //重置登录用户的ak,sk信息
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE+loginUser.getId(),userJson,JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return userDevKeyVO;
    }
    private UserDevKeyVO genKey(String userAccount){
        String accessKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(5));
        String secretKey = DigestUtil.md5Hex(SALT + userAccount + RandomUtil.randomNumbers(8));
        UserDevKeyVO userDevKeyVO = new UserDevKeyVO();
        userDevKeyVO.setAccessKey(accessKey);
        userDevKeyVO.setSecretKey(secretKey);
        return userDevKeyVO;
    }

    /**
     * 通过邮箱登录
     * @param emailNum
     * @param emailCode
     * @param request
     * @param response
     * @return
     */
    @Override
    public LoginUserVO userLoginBySms(String emailNum, String emailCode, HttpServletRequest request, HttpServletResponse response) {
        //1.校验邮箱验证码是否正确
        if (!emailCodeValid(emailNum, emailCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱验证码错误!!!");
        }

        //2.校验邮箱是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email",emailNum);
        User user = this.getOne(queryWrapper);

        if(user == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户不存在！");
        }

        return setLoginUser(response, user);
    }
    /**
     * 邮箱验证码校验
     * @param emailNum
     * @param emailCode
     * @return
     */
    private boolean emailCodeValid(String emailNum, String emailCode) {
        String code = stringRedisTemplate.opsForValue().get(LOGINCODEPRE + emailNum);
        if (StringUtils.isBlank(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式或邮箱验证码错误!!!");
        }

        if (!emailCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式或邮箱验证码错误!!!");
        }

        return true;
    }

    @Override
    public long userEmailRegister(String emailNum, String emailCaptcha) {
        if (!emailCodeValid(emailNum, emailCaptcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱格式或邮箱验证码错误!!!");
        }
        //2.校验邮箱是否已经注册过
        synchronized (emailNum.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email",emailNum);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"邮箱已经注册过了！！！账号重复");
            }

            //给用户分配调用接口的公钥和私钥ak,sk，保证复杂的同时要保证唯一
            String accessKey = DigestUtil.md5Hex(SALT+emailNum+ RandomUtil.randomNumbers(5));
            String secretKey = DigestUtil.md5Hex(SALT+emailNum+ RandomUtil.randomNumbers(8));

            // 3. 插入数据
            User user = new User();
            user.setAccessKey(accessKey);
            user.setSecretKey(secretKey);
            user.setUserName(emailNum);
            user.setEmail(emailNum);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    /**
     * 上传头像
     * @param file
     * @param request
     * @return
     */
    @Override
    public boolean uploadFileAvatar(MultipartFile file, HttpServletRequest request) {
        User loginUser = this.getLoginUser(request);
        //更新持久层用户头像信息
        User updateUser = new User();
        updateUser.setId(loginUser.getId());
        String url = FileUploadUtil.uploadFileAvatar(file);
        updateUser.setUserAvatar(url);
        boolean result = this.updateById(updateUser);

        //更新用户缓存
        loginUser.setUserAvatar(url);
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + loginUser.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);
        return result;
    }

    @Override
    public boolean updateUser(UserUpdateRequest userUpdateRequest, HttpServletRequest request) {
        //允许用户修改自己的信息，但拒绝用户修改别人的信息；但管理员可以修改别人的信息
        User loginUser = this.getLoginUser(request);
        Long id = userUpdateRequest.getId();
        if (!loginUser.getId().equals(id)){
            if (!loginUser.getUserRole().equals(UserRoleEnum.ADMIN.getValue())){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }

        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        boolean result = this.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        //修改完要更新用户缓存
        loginUser.setUserName(userUpdateRequest.getUserName());
        String userJson = gson.toJson(loginUser);
        stringRedisTemplate.opsForValue().set(USER_LOGIN_STATE + loginUser.getId(), userJson, JwtUtils.EXPIRE, TimeUnit.MILLISECONDS);

        return true;
    }


}




