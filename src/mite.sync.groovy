#!/usr/bin/env groovy

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.1' )
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.XML
import static groovyx.net.http.ContentType.TEXT

import groovy.xml.MarkupBuilder
import java.text.SimpleDateFormat

def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
def miteDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

// configure command line interface
def cli = new CliBuilder(usage: "groovy mite.sync.groovy [options] [config_file]");
cli.with {
    header = "\nIf no name for the configuration file is provided, .mite-sync is used by default."

    h(longOpt: "help", "Show help message")
    d(longOpt: "dry-run", "Don't make any changes")
    F(longOpt: "force", "Force import of time entry (overrides existing entries)")
    _(longOpt: "new-config", "Generate a new configuration file with sample content")
    _(longOpt: "start-date", args: 1, argName: "date", "Import only time entries on or after this date (e.g. ${dateFormat.format(new Date())})")
    _(longOpt: "projects", args: 1, argName: "account_name", "List all projects for account")
    _(longOpt: "services", args: 1, argName: "account_name", "List all services for account")
}

def options = cli.parse(args)
def configFile = new File(options.arguments() ? options.arguments()[0]: ".mite-sync")

if(options."new-config") {
    configFile.text = """
accounts {
    mycustomer {                     // account's name
        apiKey = "ac9b9d5f43a7e35"
    }
    myaccount {
        apiKey = "m8sc8d1dme9r027"
    }
}
projects {
    customerproject {
        source {
            account = "mycustomer"
            projectId = "1234567"
            userId = "12345"
        }
        target {
            account = "myaccount"
            projectId = "7654321"
            userId = "54321"
        }
        serviceMapping = [
            "123456": "654321",
            "123457": "754321",
            "*": "754321"            // catch-all service mapping
        ]
        note {
            search = ''              // search regular expression
            replace = ''             // replace string
            capitalize = true        // capitalize first letter
        }
    }
}
"""
    println "Generating config file '${options.arguments()[0]}'"
    return
}

// show usage message
if(!configFile.exists() || !configFile.isFile() || options.help) {
    cli.usage()
    return
}

// parse config file
def config = new ConfigSlurper().parse(configFile.toURL())

// show projects
if(options.projects) {
    def rest = new RESTClient("https://${options.projects}.mite.yo.lk")
    rest.get(path: "/projects.xml", query: [api_key: config.accounts."${options.projects}".apiKey], contentType: XML) { resp, xml ->
        xml.project.each { entry ->
            println "${entry.name.text()}:".padRight(50) + entry.id.text()
        }
    }
    return
}

// show services
if(options.services) {
    def rest = new RESTClient("https://${options.services}.mite.yo.lk")
    rest.get(path: "/services.xml", query: [api_key: config.accounts."${options.services}".apiKey], contentType: XML) { resp, xml ->
        xml.service.each { entry ->
            println "${entry.name.text()}:".padRight(50) + entry.id.text()
        }
    }
    return
}

config.projects.entrySet().each { projectEntry ->
    def project = projectEntry.value
    def source = new RESTClient("https://${project.source.account}.mite.yo.lk")
    def target = new RESTClient("https://${project.target.account}.mite.yo.lk")
    def sourceApiKey = config.accounts."${project.source.account}".apiKey
    def targetApiKey = config.accounts."${project.target.account}".apiKey

    println "Importing project '${projectEntry.key}'"

    def sourceTimeEntries = []
    def fromDate = options."start-date" ? dateFormat.parse(options."start-date") : new Date(0)
    source.get(path: "/time_entries.xml", query: [api_key: sourceApiKey, project_id: project.source.projectId, user_id: project.source.userId, from: dateFormat.format(fromDate)], contentType: XML) { resp, xml ->
        xml.'time-entry'.each { entry ->
            sourceTimeEntries << entry
        }
    }

    def targetTimeEntries = []
    target.get(path: "/time_entries.xml", query: [api_key: targetApiKey, project_id: project.target.projectId, user_id: project.target.userId, from: dateFormat.format(fromDate)], contentType: XML) { resp, xml ->
        xml.'time-entry'.each { entry ->
            targetTimeEntries << entry
        }
    }

    sourceTimeEntries = sourceTimeEntries.sort { entry ->
        miteDateFormat.parse(entry.'created-at'.text())
    }.each { entry ->
        def noteSuffix = "Imported from ${source.uri}/time_entries/${entry.id.text()}"
        def existingTimeEntry = targetTimeEntries.find {
            it.note.text().endsWith(noteSuffix)
        }

        if(!options.F && existingTimeEntry) {
            println "Skipping entry ${entry.id.text()}: Already imported."
        }
        else {
            def sourceServiceId = entry.'service-id'.text().toInteger()
            def targetServiceId = project.serviceMapping[sourceServiceId] ?: project.serviceMapping["*"]

            if(targetServiceId) {
                def theNote = (entry.'note'.text() ? entry.'note'.text() + "\n\n" : '')
                theNote = theNote?.replaceAll(project.note.search, project.note.replace)

                if(project.note.capitalize) {
                    theNote = theNote?.capitalize()
                }

                def sw = new StringWriter()
                def xml = new MarkupBuilder(sw)
                xml.'time-entry' {
                    'date-at'(entry.'date-at'.text())
                    'minutes'(entry.'minutes'.text())
                    'note'(theNote + noteSuffix)
                    'service-id'(targetServiceId)
                    'project-id'(project.target.projectId)
                }

                def xmlString = sw.toString()
                if(options.d) {
                    println xmlString
                    println()
                }
                else {
                    try {
                        if(existingTimeEntry) {
                            def response = target.put(path: "/time_entries/${existingTimeEntry.id.text()}.xml", query: [api_key: targetApiKey], contentType: TEXT, requestContentType: XML, body: xmlString)
                            if(response.statusLine.statusCode == 200) {
                                println "Successfully updated existing time entry ${existingTimeEntry.id.text()}"
                            } else {
                                println "Error updating existing time entry ${existingTimeEntry.id.text()}: ${response.statusLine}"
                            }
                        } else {
                            target.post(path: '/time_entries.xml', query: [api_key: targetApiKey], contentType: TEXT, requestContentType: XML, body: xmlString) { response ->
                                if(response.statusLine.statusCode == 201) {
                                    println "Successfully imported time entry ${entry.id.text()}"
                                } else {
                                    println "Error importing time entry ${entry.id.text()}: ${response.statusLine}"
                                }
                            }
                        }
                    }
                    catch(groovyx.net.http.HttpResponseException e) {
                        println "Error importing time entry ${entry.id.text()}: ${e.message}; ${e.response.statusLine}; ${e.response.data}"
                    }
                }
            }
            else {
                println "No target service for source service ID ${sourceServiceId} found in service mapping. Skipping..."
            }
        }
    }
}
