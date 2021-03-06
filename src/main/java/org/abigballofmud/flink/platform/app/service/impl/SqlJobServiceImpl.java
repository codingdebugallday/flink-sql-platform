package org.abigballofmud.flink.platform.app.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Resource;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.codingdebugallday.client.api.dto.NodeDTO;
import com.github.codingdebugallday.client.api.dto.UploadJarDTO;
import com.github.codingdebugallday.client.app.service.FlinkApi;
import com.github.codingdebugallday.client.domain.entity.jars.JarRunRequest;
import com.github.codingdebugallday.client.domain.entity.jars.JarRunResponseBody;
import com.github.codingdebugallday.client.domain.repository.NodeRepository;
import com.github.codingdebugallday.client.domain.repository.UploadJarRepository;
import com.github.codingdebugallday.client.infra.context.FlinkApiContext;
import com.github.codingdebugallday.client.infra.exceptions.FlinkCommonException;
import com.github.codingdebugallday.client.infra.utils.JSON;
import com.github.codingdebugallday.client.infra.utils.Preconditions;
import com.github.codingdebugallday.client.infra.utils.ThreadPoolUtil;
import lombok.extern.slf4j.Slf4j;
import org.abigballofmud.flink.platform.api.dto.SettingInfo;
import org.abigballofmud.flink.platform.api.dto.SqlJobDTO;
import org.abigballofmud.flink.platform.app.service.SqlJobService;
import org.abigballofmud.flink.platform.domain.entity.SqlJob;
import org.abigballofmud.flink.platform.domain.repository.SqlJobRepository;
import org.abigballofmud.flink.platform.infra.constants.CommonConstant;
import org.abigballofmud.flink.platform.infra.converter.SqlJobConvertMapper;
import org.abigballofmud.flink.platform.infra.mapper.SqlJobMapper;
import org.abigballofmud.flink.platform.infra.utils.CommonUtil;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * <p>
 * description
 * </p>
 *
 * @author isacc 2020/04/01 11:46
 * @since 1.0
 */
@Service
@Slf4j
public class SqlJobServiceImpl extends ServiceImpl<SqlJobMapper, SqlJob> implements SqlJobService {

    private final ExecutorService executorService = ThreadPoolUtil.getExecutorService();

    @Resource
    private StringEncryptor jasyptStringEncryptor;
    @Resource
    private FlinkApiContext flinkApiContext;

    private final NodeRepository nodeRepository;
    private final SqlJobRepository sqlJobRepository;
    private final UploadJarRepository uploadJarRepository;
    private final SqlJobMapper sqlJobMapper;

    public SqlJobServiceImpl(NodeRepository nodeRepository,
                             SqlJobRepository sqlJobRepository,
                             UploadJarRepository uploadJarRepository,
                             SqlJobMapper sqlJobMapper) {
        this.nodeRepository = nodeRepository;
        this.sqlJobRepository = sqlJobRepository;
        this.uploadJarRepository = uploadJarRepository;
        this.sqlJobMapper = sqlJobMapper;
    }

    /**
     * /data/flink/flink-1.10.0/bin/flink run \
     * -d -p 1 \
     * -c org.abigballofmud.flink.sqlsubmit.SqlSubmit \
     * /data/flink/flink-app-1.0-SNAPSHOT-jar-with-dependencies.jar \
     * -w /data/flink \
     * -f q1.sql
     */
    @Override
    public SqlJobDTO execute(Long tenantId, Long jobId, Long execJarId) {
        SqlJobDTO sqlJobDTO = sqlJobRepository.detail(tenantId, jobId);
        FlinkApi flinkApi = flinkApiContext.get(sqlJobDTO.getClusterCode(), tenantId);
        // 判断当前任务是否正在运行
        if (sqlJobDTO.getJobStatus().equalsIgnoreCase(CommonConstant.Status.RUNNING)) {
            throw new FlinkCommonException("this sql job is running, please do not repeat execute!");
        }
        // 获取执行sql任务的jar
        UploadJarDTO uploadJarDTO;
        if (Objects.nonNull(execJarId)) {
            // 指定jar运行
            uploadJarDTO = uploadJarRepository.detail(tenantId, execJarId);
        } else {
            // 默认最新版本运行
            uploadJarDTO = uploadJarRepository.findMaxVersionJarByCode(
                    CommonConstant.JarCode.FLINK_SQL_PLATFORM, sqlJobDTO.getClusterCode(), tenantId);
        }
        // 设置本次执行的jar id
        sqlJobDTO.setExecJarId(uploadJarDTO.getUploadJarId());
        // 运行jar
        SettingInfo.SqlJobSettingInfo sqlJobSettingInfo;
        if (StringUtils.isEmpty(sqlJobDTO.getSettingInfo())) {
            sqlJobSettingInfo = SettingInfo.SqlJobSettingInfo.builder().build();
        } else {
            sqlJobSettingInfo = JSON.toObj(sqlJobDTO.getSettingInfo(), SettingInfo.SqlJobSettingInfo.class);
        }
        // 二者不能都为空，其一有值即可
        List<String> programList = Stream.of("-w", sqlJobDTO.getSqlUploadPath(),
                "-f", String.format(CommonConstant.SQL_FILE_NAME, sqlJobDTO.getTenantId(), sqlJobDTO.getJobCode()))
                .collect(Collectors.toList());
        Preconditions.checkAnyNotNull(uploadJarDTO.getEntryClass(), sqlJobSettingInfo.getEntryClass());
        JarRunResponseBody jarRunResponseBody = flinkApi.runJar(JarRunRequest.builder()
                .jarId(uploadJarDTO.getJarName())
                .entryClass(Optional.ofNullable(sqlJobSettingInfo.getEntryClass()).orElse(uploadJarDTO.getEntryClass()))
                .parallelism(sqlJobSettingInfo.getParallelism())
                .allowNonRestoredState(sqlJobSettingInfo.getAllowNonRestoredState())
                .programArgsList(programList)
                .build());
        // 回写flink_job_id或错误信息
        if (CollectionUtils.isEmpty(jarRunResponseBody.getErrors())) {
            // 正常
            sqlJobDTO.setErrors("");
            sqlJobDTO.setJobStatus(CommonConstant.Status.RUNNING);
            sqlJobDTO.setFlinkJobId(jarRunResponseBody.getJobid());
        } else {
            // 异常
            sqlJobDTO.setFlinkJobId("");
            sqlJobDTO.setJobStatus(CommonConstant.Status.FAILED);
            sqlJobDTO.setErrors(String.join("\n", jarRunResponseBody.getErrors()));
        }
        updateById(SqlJobConvertMapper.INSTANCE.dtoToEntity(sqlJobDTO));
        return SqlJobConvertMapper.INSTANCE.entityToDTO(getById(sqlJobDTO.getJobId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SqlJobDTO create(SqlJobDTO sqlJobDTO) {
        // 插表
        SqlJob sqlJob = SqlJobConvertMapper.INSTANCE.dtoToEntity(sqlJobDTO);
        sqlJob.setJobStatus(CommonConstant.Status.UPLOADING);
        sqlJobMapper.insert(sqlJob);
        // 异步上传sql脚本
        String sqlFileName = String.format(CommonConstant.SQL_FILE_NAME, sqlJobDTO.getTenantId(), sqlJobDTO.getJobCode());
        uploadSqlFile(sqlFileName, getById(sqlJob.getJobId()));
        return SqlJobConvertMapper.INSTANCE.entityToDTO(sqlJob);
    }

    @Override
    public SqlJobDTO update(SqlJobDTO sqlJobDTO) {
        SqlJob sqlJob = SqlJobConvertMapper.INSTANCE.dtoToEntity(sqlJobDTO);
        updateById(sqlJob);
        getById(sqlJob.getJobId());
        // 异步上传sql脚本
        String sqlFileName = String.format(CommonConstant.SQL_FILE_NAME, sqlJobDTO.getTenantId(), sqlJobDTO.getJobCode());
        uploadSqlFile(sqlFileName, getById(sqlJob.getJobId()));
        return SqlJobConvertMapper.INSTANCE.entityToDTO(sqlJob);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long jobId) {
        // 判断状态是否是非RUNNING
        SqlJob sqlJob = Optional.ofNullable(this.getOne(new QueryWrapper<>(
                SqlJob.builder().tenantId(tenantId).jobId(jobId).build()), true))
                .orElseThrow(() -> new FlinkCommonException("can not find sql job by jobId[" + jobId + "]"));
        if (CommonConstant.Status.RUNNING.equalsIgnoreCase(sqlJob.getJobStatus())) {
            throw new FlinkCommonException("this sql job is running, please do not repeat execute!");
        }
        // 删除表记录
        removeById(jobId);
        // 异步删除服务器上的sql文件
        String sqlFileName = String.format(CommonConstant.SQL_FILE_NAME, sqlJob.getTenantId(), sqlJob.getJobCode());
        deleteSqlFile(String.format("%s/%s", sqlJob.getSqlUploadPath(), sqlFileName), sqlJob);
    }

    private void deleteSqlFile(String sqlFileName, SqlJob sqlJob) {
        // flink cluster中的sql文件异步删除
        List<NodeDTO> nodeDTOList =
                nodeRepository.selectByClusterCode(sqlJob.getClusterCode(), sqlJob.getTenantId());
        if (CollectionUtils.isEmpty(nodeDTOList)) {
            throw new FlinkCommonException("error.find.flink.cluster");
        }
        CommonUtil.deleteFileFromFlinkCluster(nodeDTOList, sqlFileName, jasyptStringEncryptor, executorService);
    }

    private void uploadSqlFile(String sqlFileName, SqlJob sqlJob) {
        // sql文件异步上传到flink cluster
        List<NodeDTO> nodeDTOList =
                nodeRepository.selectByClusterCode(sqlJob.getClusterCode(), sqlJob.getTenantId());
        if (CollectionUtils.isEmpty(nodeDTOList)) {
            throw new FlinkCommonException("error.find.flink.cluster");
        }
        CompletableFuture<Void> allCompletableFuture = CommonUtil.uploadFileToFlinkCluster(
                nodeDTOList, sqlJob.getContent(), sqlFileName,
                sqlJob.getSqlUploadPath(), jasyptStringEncryptor, executorService);
        // 上传完成后更改状态
        allCompletableFuture.thenRunAsync(() -> {
            sqlJob.setJobStatus(CommonConstant.Status.UPLOADED);
            sqlJobMapper.updateById(sqlJob);
        }, executorService);
    }

}
