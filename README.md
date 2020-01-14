# OTRS (Database) migration to JSD

# Script to perform migration

TO DO - standartise script variables and some common data like URL's

## Import data from OTRS to JSD

* otrs-import.groovy

## Check imported data

* otrs-check.groovy

## Links imported tickets together

* otrs-links.groovy


# Required preparations

## OTRS preparation

Create read only database user needs to be created to read data from OTRS MySQL or PostgreSQL database

## Set up imported data project in JSD

Create OTRS project (key OTRS, otherwise scripts needs to be adjusted)

## Import user

Set up or choose a user to execute Jira operations like create and update (default admin)


## Set up necessary Custom Fields

* "OTRS Ticket Created" - datetime
* "OTRS Ticket Details" - multiline text
* "OTRS Ticket History" - multiline text
* "OTRS Ticket Number"  - single line text
* "OTRS Ticket URL"     - URL
* "OTRS Ticket Check"   - boolean (optional for check script)

Record custom field ID's and set in import script. TO DO gather that data from Jira.

## Set up new issue link type

Create new issue link called *Relation* with *Related to* and *Parent of* links.
Get links ID's and set in links script - line 64 and 68

## Run scripts

Run scripts in sequence to copy data

First *otrs-import.groovy* to copy main data. limit could be set to main SQL to copy only small amount to trace errors.

Second is *otrs-check.groovy*. This will check data consistency. Some times wrong data in OTRS caused issues.

Last is to run linking script to set parent child relation.
OTRS keeps each reply or comment in articles but in main view it shows it as a thread.
It was hard to replicate same in Jira so issues are linked together as *Parent of* and *Related to* links.
