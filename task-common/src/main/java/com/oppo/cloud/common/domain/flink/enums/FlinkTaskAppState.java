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

package com.oppo.cloud.common.domain.flink.enums;

import lombok.Getter;

/**
 * 实时作业运行状态
 */
@Getter
public enum FlinkTaskAppState {
    /**
     * 运行状态
     */
    RUNNING(0, "RUNNING"),
    /**
     * 结束状态
     */
    FINISHED(1, "FINISHED"),
    ;
    private final int code;
    private final String desc;

    FlinkTaskAppState(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}