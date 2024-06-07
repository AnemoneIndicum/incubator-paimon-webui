/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.web.server.service.impl;

import org.apache.paimon.web.engine.flink.sql.gateway.client.SqlGatewayClient;
import org.apache.paimon.web.engine.flink.sql.gateway.model.SessionEntity;
import org.apache.paimon.web.server.data.dto.SessionDTO;
import org.apache.paimon.web.server.service.SessionService;
import org.apache.paimon.web.server.service.UserService;
import org.apache.paimon.web.server.service.UserSessionManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** The implementation of {@link SessionService}. */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Integer ACTIVE_STATUS = 1;
    private static final Integer INACTIVE_STATUS = 0;

    @Autowired private UserSessionManager sessionManager;

    @Autowired private UserService userService;

    /**
     * 创建会话。
     * 根据传入的会话DTO（数据传输对象），通过SQL Gateway客户端打开一个新的会话。如果用户ID不为空，
     * 则会根据用户ID获取用户名，生成唯一的会话名称，并尝试在指定的集群上创建会话。如果当前用户和集群上没有
     * 存在的会话，或者当前会话心跳已过期，则创建新的会话实体并添加到会话管理器中。
     *
     * @param sessionDTO 会话的详细信息，包括主机、端口、用户ID和集群ID等。
     * @throws RuntimeException 如果创建会话过程中发生异常，则抛出运行时异常。
     */
    @Override
    public void createSession(SessionDTO sessionDTO) {
        try {
            SqlGatewayClient client =
                    new SqlGatewayClient(sessionDTO.getHost(), sessionDTO.getPort());
            if (sessionDTO.getUid() != null) {
                String username = userService.getUserById(sessionDTO.getUid()).getUsername();
                String sessionName = username + "_" + UUID.randomUUID();
                if (getSession(sessionDTO.getUid(), sessionDTO.getClusterId()) == null
                        || triggerSessionHeartbeat(sessionDTO) < 1) {
                    SessionEntity sessionEntity = client.openSession(sessionName);
                    sessionManager.addSession(
                            sessionDTO.getUid() + "_" + sessionDTO.getClusterId(), sessionEntity);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create session", e);
        }
    }

    @Override
    public void closeSession(SessionDTO sessionDTO) {
        try {
            SqlGatewayClient client =
                    new SqlGatewayClient(sessionDTO.getHost(), sessionDTO.getPort());
            if (sessionDTO.getUid() != null) {
                SessionEntity session =
                        sessionManager.getSession(
                                sessionDTO.getUid() + "_" + sessionDTO.getClusterId());
                if (session != null) {
                    client.closeSession(session.getSessionId());
                    sessionManager.removeSession(
                            sessionDTO.getUid() + "_" + sessionDTO.getClusterId());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to close session", e);
        }
    }

    /**
     * 触发会话心跳检测。
     * 该方法用于向指定的会话发送心跳请求，以确保会话处于活跃状态。
     * 如果会话相关信息有效，则通过SqlGatewayClient向会话发送心跳。
     * 如果在执行过程中发生异常，则视为会话不活跃。
     *
     * @param sessionDTO 会话数据传输对象，包含会话的唯一标识、主机地址、端口号等信息。
     * @return 如果会话触发心跳成功或无需触发，则返回活跃状态码；如果发生异常，则返回不活跃状态码。
     */
    @Override
    public int triggerSessionHeartbeat(SessionDTO sessionDTO) {
        try {
            if (sessionDTO.getUid() != null) {
                SqlGatewayClient client =
                        new SqlGatewayClient(sessionDTO.getHost(), sessionDTO.getPort());
                SessionEntity session =
                        sessionManager.getSession(
                                sessionDTO.getUid() + "_" + sessionDTO.getClusterId());
                client.triggerSessionHeartbeat(session.getSessionId());
            }
        } catch (Exception e) {
            return INACTIVE_STATUS;
        }
        return ACTIVE_STATUS;
    }

    @Override
    public SessionEntity getSession(Integer uid, Integer clusterId) {
        return sessionManager.getSession(uid + "_" + clusterId);
    }
}
