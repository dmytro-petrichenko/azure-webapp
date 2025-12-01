package com.productmadness

class CommonScripts implements Serializable
{
	def steps

    CommonScripts(steps) {
		this.steps = steps
	}

	String getParameterList() {
		String parameters = ""
		if (steps.params.IOS) {
			parameters = parameters + "ios "
		}
		if (steps.params.ANDROID) {
			parameters = parameters + "android "
		}
		return parameters
	}

	def renameWindowsInstance()
	{
		steps.stage("Rename Machine")
		{
			def agentName = "${steps.NODE_NAME}".split("-").last()
			def powershellFile = """
            	\$ComputerName = "agent-${agentName}"

            	Remove-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters" -name "Hostname"
            	Remove-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters" -name "NV Hostname"

            	Set-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Computername\\Computername" -name "Computername" -value \$ComputerName
            	Set-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Computername\\ActiveComputername" -name "Computername" -value \$ComputerName
            	Set-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters" -name "Hostname" -value \$ComputerName
            	Set-ItemProperty -path "HKLM:\\SYSTEM\\CurrentControlSet\\Services\\Tcpip\\Parameters" -name "NV Hostname" -value  \$ComputerName
            	Set-ItemProperty -path "HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon" -name "AltDefaultDomainName" -value \$ComputerName
            	Set-ItemProperty -path "HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon" -name "DefaultDomainName" -value \$ComputerName
        	"""

			steps.writeFile file: "rename.ps1", text: "${powershellFile}"

			def batFile = """
            	powershell -ExecutionPolicy Unrestricted -File rename.ps1 --settings_skipverification=true
        	"""

			steps.writeFile file: "rename.bat", text: "${batFile}"

			steps.bat "rename.bat"
		}
	}

	def renameLinuxInstance()
	{
		steps.stage("Rename Machine")
		{
			String agentName = "${steps.NODE_NAME}".split("-").last()
			String newName = "agent-${agentName}"
			steps.sh("sudo hostnamectl set-hostname $newName")
		}
	}
	def waitForInstanceToBecomeReady()
	{
        def markerPath
		try {
			if(steps.env.OS_TYPE.toLowerCase().contains("windows")) {
				markerPath = "C:\\StartupDone\\jenkins_startup_done.txt"
				steps.timeout(time: 2, unit: 'MINUTES') {
					steps.waitUntil {
						steps.bat(script: "if exist ${markerPath} (exit 0) else (exit 1)", returnStatus: true) == 0
					}
				}
			}
			else {
				markerPath = "/home/jenkins/startup_done/jenkins_startup_done.txt"
				steps.timeout(time: 2, unit: 'MINUTES') {
					steps.waitUntil {
						steps.sh(script: "if [ -f ${markerPath} ]; then exit 0; else exit 1; fi", returnStatus: true) == 0
					}
				}
			}

			steps.echo "Startup script completed, marker found."
		} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
			steps.error("Startup script did not finish within 2 minutes. Marker file not found at ${markerPath}.")
		}
	}

    def getSecretFromOnePassword(def vaultId, def itemId, def field, def section = null)
    {
        def onePasswordConnectUrl = steps.env.ONE_PASSWORD_CONNECT_URL ?: "http://onepassword-connect.apptech.alabs.aristocrat.com:8080"
        def onePasswordConnectToken = steps.env.ONE_PASSWORD_CONNECT_TOKEN ?: "${steps.credentials('one-password-connect-token')}"

        def jqItemFilter = section?.trim() ?
                "select(.label==\"${field}\" and .section.label==\"${section}\")" :
                "select(.label==\"${field}\")"

        def command = "curl -s -H \"Authorization: Bearer ${onePasswordConnectToken}\" ${onePasswordConnectUrl}/v1/vaults/${vaultId}/items/${itemId} | jq -r '.fields[] | ${jqItemFilter} | .value'"
        def value = steps.sh(returnStdout: true, script: """#!/bin/bash -l
                                       ${command}
                                     """).trim()
        return value
    }
}
