package org.abigballofmud.flink.platform.app.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.abigballofmud.flink.platform.api.dto.SqlJobDTO;
import org.abigballofmud.flink.platform.domain.entity.SqlJob;

/**
 * <p>
 * description
 * </p>
 *
 * @author isacc 2020/04/01 11:45
 * @since 1.0
 */
public interface SqlJobService extends IService<SqlJob> {

    /**
     * 创建flink sql job
     *
     * @param sqlJobDTO SqlJobDTO
     * @return org.abigballofmud.flink.platform.api.dto.SqlJobDTO
     */
    SqlJobDTO create(SqlJobDTO sqlJobDTO);

    /**
     * 更新flink sql job
     *
     * @param sqlJobDTO SqlJobDTO
     * @return org.abigballofmud.flink.platform.api.dto.SqlJobDTO
     */
    SqlJobDTO update(SqlJobDTO sqlJobDTO);

    /**
     * 执行flink sql任务
     *
     * @param tenantId    租户id
     * @param jobId       jobId
     * @param execJarId 指定使用哪个上传的jar去执行sql任务
     * @return org.abigballofmud.flink.platform.api.dto.SqlJobDTO
     */
    SqlJobDTO execute(Long tenantId, Long jobId, Long execJarId);
}
