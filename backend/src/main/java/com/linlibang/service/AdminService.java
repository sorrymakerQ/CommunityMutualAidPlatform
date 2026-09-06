package com.linlibang.service;

import com.linlibang.dto.Result;

public interface AdminService {
    Result getStats();
    Result getUserList(Integer page, Integer size);
    Result updateUserStatus(Long id, Integer status);
    Result updateUserRole(Long id, Long roleId);
    Result getRoleList();
    Result getHelpList(Integer page, Integer size, Integer status);
    Result deleteHelp(Long id);
}
