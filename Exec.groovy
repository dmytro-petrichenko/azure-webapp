package com.productmadness

class Exec implements Serializable
{
	def steps

	Exec(steps) {
		this.steps = steps
	}

	def executeCommand(String command, boolean logCommand = false)
	{
		if (logCommand) {
			steps.println(command)
		}
		
		if (steps.env.OS_TYPE.toLowerCase().contains("windows"))
		{
			steps.powershell("""
				& ${command}
			""")
		} else
		{
			steps.sh("""#!/bin/bash -l
				${command}
			""")
		}
	}
}
