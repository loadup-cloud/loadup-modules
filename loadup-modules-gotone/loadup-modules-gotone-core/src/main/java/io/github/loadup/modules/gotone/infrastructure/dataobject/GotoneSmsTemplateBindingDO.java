/*
 * Copyright (C) 2025-2026 LoadUp Cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loadup.modules.gotone.infrastructure.dataobject;

import com.mybatisflex.annotation.Table;
import io.github.loadup.components.database.dataobject.BaseDO;

@Table("gotone_sms_template_binding")
public class GotoneSmsTemplateBindingDO extends BaseDO {
    private String mapId;
    private String accountCode;
    private String remoteTemplateId;
    private String remoteSignature;
    private String variableMapping;
    private String serviceCode;
    private Integer revision;

    public String getMapId() {
        return mapId;
    }

    public void setMapId(String mapId) {
        this.mapId = mapId;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getRemoteTemplateId() {
        return remoteTemplateId;
    }

    public void setRemoteTemplateId(String remoteTemplateId) {
        this.remoteTemplateId = remoteTemplateId;
    }

    public String getRemoteSignature() {
        return remoteSignature;
    }

    public void setRemoteSignature(String remoteSignature) {
        this.remoteSignature = remoteSignature;
    }

    public String getVariableMapping() {
        return variableMapping;
    }

    public void setVariableMapping(String variableMapping) {
        this.variableMapping = variableMapping;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }
}
