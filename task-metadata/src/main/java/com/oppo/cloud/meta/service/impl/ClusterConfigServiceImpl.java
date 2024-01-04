/*
 * Copyright 2023 OPPO.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oppo.cloud.meta.service.impl;

import com.alibaba.fastjson2.JSON;
import com.oppo.cloud.common.constant.Constant;
import com.oppo.cloud.common.domain.cluster.hadoop.YarnConf;
import com.oppo.cloud.common.service.RedisService;
import com.oppo.cloud.common.util.YarnUtil;
import com.oppo.cloud.meta.config.HadoopConfig;
import com.oppo.cloud.meta.domain.HistoryInfoProperties;
import com.oppo.cloud.meta.domain.YarnPathInfo;
import com.oppo.cloud.meta.domain.Properties;
import com.oppo.cloud.meta.domain.YarnConfProperties;
import com.oppo.cloud.meta.service.IClusterConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YARN and Spark Cluster Address Configuration Information
 */
@Slf4j
@Service
public class ClusterConfigServiceImpl implements IClusterConfigService {

    @Resource
    private RedisService redisService;

    @Resource
    private HadoopConfig config;

    @Resource(name = "restTemplate")
    private RestTemplate restTemplate;

    private static final String YARN_CONF = "http://%s/conf";

    private static final String HISTORY_INFO_API = "http://%s/ws/v1/history/info";

    private static final String DEFAULT_FS = "fs.defaultFS";

    private static final String YARN_REMOTE_APP_LOG_DIR = "yarn.nodemanager.remote-app-log-dir";

    private static final String YARN_MAPREDUCE_STAGING_DIR = "yarn.app.mapreduce.am.staging-dir";

    private static final String MARREDUCE_DONE_DIR = "mapreduce.jobhistory.done-dir";

    private static final String MARREDUCE_INTERMEDIATE_DONE_DIR = "mapreduce.jobhistory.intermediate-done-dir";

    private static final String HADOOP_FILE_CONTROLLER_START_VERSION = "3.2.1";

    private static final String HADOOP_FILE_CONTROLLER_END_VERSION = "3.2.4";


    /**
     * Get spark history servers
     */
    @Override
    public List<String> getSparkHistoryServers() {
        return config.getSpark().getSparkHistoryServer();
    }

    /**
     * Get Yarn clusters
     */
    @Override
    public Map<String, String> getYarnClusters() {
        List<YarnConf> yarnConfList = config.getYarn();
        return YarnUtil.getYarnClusters(yarnConfList);
    }


    /**
     * Update cluster config
     */
    @Override
    public void updateClusterConfig() {

        log.info("clusterConfig:{}", config);
        // cache spark history server
        List<String> sparkHistoryServerList = config.getSpark().getSparkHistoryServer();
        log.info("{}:{}", Constant.SPARK_HISTORY_SERVERS, sparkHistoryServerList);
        redisService.set(Constant.SPARK_HISTORY_SERVERS, JSON.toJSONString(sparkHistoryServerList));

        // cache yarn server
        List<YarnConf> yarnConfList = config.getYarn();
        // the jobHistoryServer corresponding to resourceManager
        Map<String, String> rmJhsMap = new HashMap<>();
        yarnConfList.forEach(clusterInfo -> clusterInfo.getResourceManager()
                .forEach(rm -> rmJhsMap.put(rm, clusterInfo.getJobHistoryServer())));
        redisService.set(Constant.YARN_CLUSTERS, JSON.toJSONString(sparkHistoryServerList));
        log.info("{}:{}", Constant.YARN_CLUSTERS, yarnConfList);
        redisService.set(Constant.RM_JHS_MAP, JSON.toJSONString(rmJhsMap));
        log.info("{}:{}", Constant.RM_JHS_MAP, rmJhsMap);
        updateJHSConfig(yarnConfList);
    }

    /**
     * Update the jobhistoryserver hdfs path information in the configuration
     */
    public void updateJHSConfig(List<YarnConf> list) {
        for (YarnConf yarnClusterInfo : list) {
            String host = yarnClusterInfo.getJobHistoryServer();
            YarnPathInfo yarnPathInfo = getYarnPathInfo(host);
            if (yarnPathInfo == null) {
                log.error("get {}, hdfsPath empty", host);
                continue;
            }
            String remotePathKey = Constant.JHS_HDFS_PATH + host;
            String suffixPathKey = Constant.JHS_HDFS_SUFFIX_PATH + host;
            String mapreduceStagingPathKey = Constant.JHS_MAPREDUCE_STAGING_PATH + host;
            String mapreduceDonePathKey = Constant.JHS_MAPREDUCE_DONE_PATH + host;
            String mapreduceIntermediateDonePathKey = Constant.JHS_MAPREDUCE_INTERMEDIATE_DONE_PATH + host;
            String suffixPath = getDifferentHadoopSuffixDir(host);
            log.info("cache yarnPathInfo:{},{}", remotePathKey, yarnPathInfo.getRemoteDir());
            log.info("cache yarnPathInfo:{},{}", suffixPathKey, suffixPath);
            log.info("cache yarnPathInfo:{},{}", mapreduceStagingPathKey, yarnPathInfo.getMapreduceStagingDir());
            log.info("cache yarnPathInfo:{},{}", mapreduceDonePathKey, yarnPathInfo.getMapreduceDoneDir());
            log.info("cache yarnPathInfo:{},{}", mapreduceIntermediateDonePathKey, yarnPathInfo.getMapreduceIntermediateDoneDir());
            redisService.set(remotePathKey, yarnPathInfo.getRemoteDir());
            redisService.set(suffixPathKey, suffixPath);
            redisService.set(mapreduceStagingPathKey, yarnPathInfo.getMapreduceStagingDir());
            redisService.set(mapreduceDonePathKey, yarnPathInfo.getMapreduceDoneDir());
            redisService.set(mapreduceIntermediateDonePathKey, yarnPathInfo.getMapreduceIntermediateDoneDir());
        }
    }

    /**
     * Get jobhistoryserver hdfs path information
     */
    public YarnPathInfo getYarnPathInfo(String ip) {
        String url = String.format(YARN_CONF, ip);
        log.info("getHDFSPath:{}", url);
        ResponseEntity<String> responseEntity;
        try {
            responseEntity = restTemplate.getForEntity(url, String.class);
        } catch (Exception e) {
            log.error("getHDFSPathErr:{},{}", url, e.getMessage());
            return null;
        }

        if (responseEntity.getBody() == null) {
            log.error("getHDFSPathErr:{}", url);
            return null;
        }

        YarnConfProperties yarnConfProperties = null;
        try {
            yarnConfProperties = JSON.parseObject(responseEntity.getBody(), YarnConfProperties.class);
        } catch (Exception e) {
            log.error("Exception:", e);
            return null;
        }

        String remoteDir = "";
        String defaultFS = "";
        String mapreduceStagingDir = "";
        String mapreduceDoneDir = "";
        String mapreduceIntermediateDoneDir = "";

        if (yarnConfProperties != null && yarnConfProperties.getProperties() != null) {
            for (Properties properties : yarnConfProperties.getProperties()) {
                String key = properties.getKey();
                String value = properties.getValue();
                if (YARN_REMOTE_APP_LOG_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", YARN_REMOTE_APP_LOG_DIR, value);
                    remoteDir = value;
                }
                if (DEFAULT_FS.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", DEFAULT_FS, value);
                    defaultFS = value;
                }
                if (MARREDUCE_DONE_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", MARREDUCE_DONE_DIR, value);
                    mapreduceDoneDir = value;
                }
                if (MARREDUCE_INTERMEDIATE_DONE_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", MARREDUCE_INTERMEDIATE_DONE_DIR, value);
                    mapreduceIntermediateDoneDir = value;
                }
                if (YARN_MAPREDUCE_STAGING_DIR.equals(key)) {
                    log.info("yarnConfProperties key: {}, value: {}", YARN_MAPREDUCE_STAGING_DIR, value);
                    mapreduceStagingDir = value;
                }
            }
        }
        if (StringUtils.isEmpty(defaultFS)) {
            log.error("defaultFSEmpty:{}", url);
            return null;
        }
        if (StringUtils.isEmpty(remoteDir)) {
            log.error("remoteDirEmpty:{}", url);
            return null;
        }
        if (StringUtils.isEmpty(mapreduceDoneDir)) {
            log.error("mapreduceDoneDirEmpty:{}", url);
            return null;
        }
        if (!remoteDir.contains(Constant.HDFS_SCHEME)) {
            remoteDir = defaultFS + remoteDir;
        }
        if (!mapreduceStagingDir.contains(Constant.HDFS_SCHEME)) {
            mapreduceStagingDir = defaultFS + mapreduceStagingDir;
        }
        if (!mapreduceDoneDir.contains(Constant.HDFS_SCHEME)) {
            mapreduceDoneDir = defaultFS + mapreduceDoneDir;
        }
        if (!mapreduceIntermediateDoneDir.contains(Constant.HDFS_SCHEME)) {
            mapreduceIntermediateDoneDir = defaultFS + mapreduceIntermediateDoneDir;
        }

        YarnPathInfo yarnPathInfo = new YarnPathInfo();
        yarnPathInfo.setDefaultFS(defaultFS);
        yarnPathInfo.setRemoteDir(remoteDir);
        yarnPathInfo.setMapreduceStagingDir(mapreduceStagingDir);
        yarnPathInfo.setMapreduceDoneDir(mapreduceDoneDir);
        yarnPathInfo.setMapreduceIntermediateDoneDir(mapreduceIntermediateDoneDir);
        log.info("yarnPathInfo: {}, {}", url, yarnPathInfo);
        return yarnPathInfo;
    }


    public String getDifferentHadoopSuffixDir(String ip) {
        String version = getHadoopVersion(ip);
        log.info("hadoop version:{},{}", ip, version);
        if (StringUtils.isBlank(version)) {
            return "logs";
        }
        switch (compareVersion(version, HADOOP_FILE_CONTROLLER_START_VERSION, HADOOP_FILE_CONTROLLER_END_VERSION)) {
            case -1:
                // version < 3.2.1
                return "logs";
            case 0:
                // 3.2.1 <= version <= 3.2.4
                return "logs-tfile";
            case 1:
                // version >= 3.3.0
                return "bucket-logs-tfile";
            default:
                break;
        }
        return "logs";
    }

    public static int compareVersion(String version, String startVersion, String endVersion) {
        if (version.equals(startVersion) || version.equals(endVersion)) {
            return 0;
        }
        String[] v1 = version.split("\\.");
        String[] v2 = startVersion.split("\\.");
        String[] v3 = endVersion.split("\\.");
        if (v1.length != v2.length) {
            return -1;
        }
        for (int i = 0; i < v1.length; i++) {
            int d1 = Integer.parseInt(v1[i]);
            int d2 = Integer.parseInt(v2[i]);
            int d3 = Integer.parseInt(v3[i]);
            if (d1 < d2) {
                return -1;
            }
            if (d1 > d3) {
                return 1;
            }
            if (d1 > d2 && d1 < d3) {
                return 0;
            }
        }
        return -1;
    }

    public String getHadoopVersion(String ip) {
        String url = String.format(HISTORY_INFO_API, ip);
        log.info("get history info: {}", url);
        ResponseEntity<String> responseEntity;
        try {
            responseEntity = restTemplate.getForEntity(url, String.class);
        } catch (Exception e) {
            log.error("Exception:{}", url, e);
            return null;
        }

        if (responseEntity.getBody() == null) {
            log.error("get history info err: {}", url);
            return null;
        }

        HistoryInfoProperties historyInfo = null;
        try {
            historyInfo = JSON.parseObject(responseEntity.getBody(), HistoryInfoProperties.class);
        } catch (Exception e) {
            log.error("Exception:{}", url, e);
            return null;
        }
        if (historyInfo != null && historyInfo.getHistoryInfo() != null) {
            return historyInfo.getHistoryInfo().getHadoopVersion();
        }
        return null;
    }


}
