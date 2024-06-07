package com.waterbird.wbapicommon.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 批量删除请求
 *

 */
@Data
public class IdRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    private static final long serialVersionUID = 1L;
}