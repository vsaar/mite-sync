# mite-sync
mite-sync is a Groovy script that imports time entries from one mite account into another. It is meant for people who track their time using their own account, but are required to use a customer’s account for example.

# Configuration
Accounts and projects to be synced are defined using a separate configuration file:

```
accounts {
    mycustomer {                     // first account's name
        apiKey = "ac9b9d5f43a7e35"
    }
    myaccount {                      // second account’s name
        apiKey = "m8sc8d1dme9r027"
    }
}
projects {
    customerproject {                // project short name
        source {
            account = "mycustomer"   // source account reference
            projectId = "1234567"
            userId = "12345"
        }
        target {
            account = "myaccount"    // target account reference
            projectId = "7654321"
            userId = "54321"
        }
        serviceMapping = [           // mapping from source to target service
            "123456": "654321",      // source: target
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
```

You can easily generate a sample configuration file using the following command:

```
groovy mite.sync.groovy --new-config .my-config
```

# Syncing time entries
To import time entries into your target account, simply run the script with your configuration file. The following additional options are available:

* -h, --help: Show usage information
* -d, --dry-run: Do a dry-run without actually importing any time entries
* -F, --force: Update existing time entries (already imported entries are skipped otherwise)
* --start-date: Import only time entries on or after this date (all of the projects time entries are imported otherwise)

# Other
In addition to syncing time entries, the script can help you in setting up your configuration file. Use the following command your list projects and their IDs for a certain account:

```
groovy mite.sync.groovy --projects mycustomer
```

And use the following to list all services and their IDs:

```
groovy mite.sync.groovy --projects mycustomer
```

# Execute as shell script
If you’re on a *nix-like system (probably working on Cygwin, as well), you can make the script executable and run it directly:

```
chmod a+x mite.sync.groovy
./mite.sync.groovy
```