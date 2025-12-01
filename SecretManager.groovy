package com.productmadness

class SecretManager implements Serializable
{
    def steps

    SecretManager(steps) {
        this.steps = steps
    }

    def getSecretFromOnePassword(def vaultName, def title, def field, def section = null)
    {
        def onePasswordConnectUrl = steps.env.ONE_PASSWORD_CONNECT_URL ?: "http://onepassword-connect.apptech.alabs.aristocrat.com:8080"

        steps.withCredentials([steps.string(credentialsId: 'one-password-connect-token', variable: 'FALLBACK_ONE_PASSWORD_CONNECT_TOKEN')]) {
            def onePasswordConnectToken = steps.env.ONE_PASSWORD_CONNECT_TOKEN ?: steps.env.FALLBACK_ONE_PASSWORD_CONNECT_TOKEN
            def vaultId = getVaultId(vaultName, onePasswordConnectToken, onePasswordConnectUrl)
            def titleId = getTitleId(title, vaultId, onePasswordConnectToken, onePasswordConnectUrl)
            return getSecret(vaultId, titleId, field, section, onePasswordConnectToken, onePasswordConnectUrl)
        }
    }

    private def getVaultId(def vaultName, def onePasswordConnectToken, def onePasswordConnectUrl)
    {
        def command
        if(steps.env.OS_TYPE.toLowerCase().contains("windows")) {
            command = "curl.exe -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults | jq -r '.[] | select(.name==\\\"${vaultName}\\\") | .id'"
        }
        else {
            command = "curl -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults | jq -r '.[] | select(.name==\"${vaultName}\") | .id'"
        }

        return getCommandOutput(command)
    }

    private def getTitleId(def title, def vaultId, def onePasswordConnectToken, def onePasswordConnectUrl)
    {
        def command
        if(steps.env.OS_TYPE.toLowerCase().contains("windows")) {
            command = "curl.exe -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults/${vaultId}/items | jq -r '.[] | select(.title==\\\"${title}\\\") | .id'"
        }
        else {
            command = "curl -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults/${vaultId}/items | jq -r '.[] | select(.title==\"${title}\") | .id'"
        }

        return getCommandOutput(command)
    }

    private def getSecret(def vaultId, def titleId, def field, def section, def onePasswordConnectToken, def onePasswordConnectUrl)
    {
        def command
        def jqItemFilter = getJqSelectItemFilter(field, section)

        if(steps.env.OS_TYPE.toLowerCase().contains("windows")) {
            command = "curl.exe -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults/${vaultId}/items/${titleId} | jq -r '.fields[] | ${jqItemFilter} | .value'"
        }
        else {
            command = "curl -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults/${vaultId}/items/${titleId} | jq -r '.fields[] | ${jqItemFilter} | .value'"
        }

        return getCommandOutput(command)
    }

    private def getJqSelectItemFilter(def field, def section)
    {
        def hasSection = section?.trim()
        if(steps.env.OS_TYPE.toLowerCase().contains("windows")) {
            return hasSection ? "select(.label==\\\"${field}\\\" and .section.label==\\\"${section}\\\")" :
                                "select(.label==\\\"${field}\\\")"

        }
        else {
            return hasSection ? "select(.label==\"${field}\" and .section.label==\"${section}\")" :
                                "select(.label==\"${field}\")"
        }
    }

    private getCommandOutput(def command) {
        def value
        if (steps.env.OS_TYPE.toLowerCase().contains("windows"))
        {
            value = steps.powershell(returnStdout: true, script: """
				& ${command}
			""").trim()
        } else
        {
            value = steps.sh(returnStdout: true, script: """#!/bin/bash -l
				${command}
			""").trim()
        }
        return value
    }
}