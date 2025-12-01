package com.productmadness

import groovy.json.JsonSlurper

class GitApi implements Serializable
{
	def steps

    GitApi(steps) {
		this.steps = steps
	}

	private def getCommitData()
	{
		steps.withCredentials([steps.string( credentialsId: steps.env.CREDENTIAL_ID, variable: "TOKEN")])
		{
			if (steps.env.TOKEN.length() == 0)
			{
				steps.error "Failed to set the credentials"
			}

			HttpURLConnection connection
			try
			{
                def branch
                if(steps.env.CHANGE_BRANCH)
                {
                    branch = steps.env.CHANGE_BRANCH
                }
                else
                {
                    branch = steps.env.BRANCH_NAME
                }
				def apiURL = new URL("https://api.github.com/repos/${steps.env.ORGANISATION}/${steps.env.REPO}/commits/${branch}")

				connection = apiURL.openConnection()
				connection.setRequestProperty("Authorization", "Bearer ${steps.env.TOKEN}")
				connection.setRequestMethod("GET")
				connection.setDoOutput(true)
				connection.connect()

				def rs = new JsonSlurper().parse(new InputStreamReader(connection.getInputStream(), "UTF-8"))
				connection.disconnect()
				if (!rs)
				{
					return null
				}
				return rs
			}
			catch(err)
			{
				steps.echo "Error getting commits : ${err}"
				return null
			}
		}
	}

	def getLatestCommit()
	{
		return getCommitData().sha
	}

	def getCommitter()
	{
		return getCommitData().commit.committer.name
	}
}
