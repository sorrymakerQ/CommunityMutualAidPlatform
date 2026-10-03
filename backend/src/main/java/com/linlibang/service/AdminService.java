package com.linlibang.service;

import com.linlibang.dto.Result;

public interface AdminService {
    Result getStats();
    Result getUserList(Integer page, Integer size);
    Result updateUserStatus(Long id, Integer status);
    Result updateUserRole(Long id, Long roleId);
    /** 踢用户下线（删除该账号全部 token 与会话） */
    Result kickoutUser(Long id);
    Result getRoleList();
    Result getHelpList(Integer page, Integer size, Integer status);
    /** 修改任意用户求助的状态（含下架：status=4） */
    Result updateHelpStatus(Long id, Integer status);
    Result deleteHelp(Long id);
}
