import groovy.sql.Sql
import java.sql.Driver
import com.atlassian.jira.component.ComponentAccessor
import org.springframework.util.FileCopyUtils
import com.atlassian.jira.issue.attachment.CreateAttachmentParamsBean
import java.time.LocalDateTime
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.issue.search.SearchProvider
import org.apache.commons.io.FilenameUtils


// Issue Service
def issueService      = ComponentAccessor.getIssueService()
// Comment Manager
def commentManager    = ComponentAccessor.getCommentManager()
//  Attachment Manager
def attachmentManager = ComponentAccessor.getAttachmentManager()

// Project ID and Admin User
def projectid         = ComponentAccessor.getProjectManager().getProjectByCurrentKey("OTRS").getId()
def user              = ComponentAccessor.getUserManager().getUserByKey("admin")

// Search Service
def jqlQueryParser    = ComponentAccessor.getComponent(JqlQueryParser.class)
def searchProvider    = ComponentAccessor.getComponent(SearchProvider.class)


// Converting History
def history_format = new LinkedHashMap()
history_format.put('Move', 'Ticket moved into Queue "%s" (%s) from Queue "%s" (%s).')
history_format.put('TypeUpdate', 'Updated Type to %s (ID=%s).')
history_format.put('ServiceUpdate', 'Updated Service to %s (ID=%s).')
history_format.put('SLAUpdate', 'Updated SLA to %s (ID=%s).')
history_format.put('NewTicket', 'New Ticket [%s] created (Q=%s;P=%s;S=%s).')
history_format.put('FollowUp', 'FollowUp for [%s].')
history_format.put('SendAutoReject', 'AutoReject sent to "%s".')
history_format.put('SendAutoReply', 'AutoReply sent to "%s".')
history_format.put('SendAutoFollowUp', 'AutoFollowUp sent to "%s".')
history_format.put('Forward', 'Forwarded to "%s".')
history_format.put('Bounce', 'Bounced to "%s".')
history_format.put('SendAnswer', 'Email sent to "%s".')
history_format.put('SendAgentNotification', '"%s"-notification sent to "%s".')
history_format.put('SendCustomerNotification', 'Notification sent to "%s".')
history_format.put('EmailAgent', 'Email sent to customer.')
history_format.put('EmailCustomer', 'Added email. %s')
history_format.put('PhoneCallAgent', 'Agent called customer.')
history_format.put('PhoneCallCustomer', 'Customer called us.')
history_format.put('AddNote', 'Added note (%s)')
history_format.put('Lock', 'Locked ticket.')
history_format.put('Unlock', 'Unlocked ticket.')
history_format.put('TimeAccounting', '%s time unit(s) accounted. Now total %s time unit(s).')
history_format.put('Remove', '%s')
history_format.put('CustomerUpdate', 'Updated: %s')
history_format.put('PriorityUpdate', 'Changed priority from "%s" (%s) to "%s" (%s).')
history_format.put('OwnerUpdate', 'New owner is "%s" (ID=%s).')
history_format.put('ResponsibleUpdate', 'New responsible is "%s" (ID=%s).')
history_format.put('LoopProtection', 'Loop-Protection! No auto-response sent to "%s".')
history_format.put('Misc', '%s')
history_format.put('SetPendingTime', 'Updated: %s')
history_format.put('StateUpdate', 'Old: "%s" New: "%s"')
history_format.put('TicketDynamicFieldUpdate', 'Updated: %s=%s;%s=%s;')
history_format.put('WebRequestCustomer', 'Customer request via web.')
history_format.put('TicketLinkAdd', 'Added link to ticket "%s".')
history_format.put('TicketLinkDelete', 'Deleted link to ticket "%s".')
history_format.put('Subscribe', 'Added subscription for user "%s".')
history_format.put('Unsubscribe', 'Removed subscription for user "%s".')
history_format.put('SystemRequest', 'System Request (%s).')
history_format.put('EscalationResponseTimeNotifyBefore', 'Escalation response time forewarned')
history_format.put('EscalationUpdateTimeNotifyBefore', 'Escalation update time forewarned')
history_format.put('EscalationSolutionTimeNotifyBefore', 'Escalation solution time forewarned')
history_format.put('EscalationResponseTimeStart', 'Escalation response time in effect')
history_format.put('EscalationUpdateTimeStart', 'Escalation update time in effect')
history_format.put('EscalationSolutionTimeStart', 'Escalation solution time in effect')
history_format.put('EscalationResponseTimeStop', 'Escalation response time finished')
history_format.put('EscalationUpdateTimeStop', 'Escalation update time finished')
history_format.put('EscalationSolutionTimeStop', 'Escalation solution time finished')
history_format.put('ArchiveFlagUpdate', 'Archive state changed: "%s"')


def driver = Class.forName('com.mysql.jdbc.Driver').newInstance() as Driver 

def props = new Properties()
props.setProperty("user",     "otrs_ro") 
props.setProperty("password", "otrs"   )

def conn = driver.connect("jdbc:mysql://otrs-mysql:3306/otrs", props) 
def ticket         = new Sql(conn)
def ticket_history = new Sql(conn)
def article        = new Sql(conn)
def article_att    = new Sql(conn)

try {
    //log.warn("start")
    def startTime = LocalDateTime.now()
    def tkNum = 0
    def tpNum = 0
    def ticket_sql = """SELECT tk.id, tk.tn, tk.title, tk.create_time, tk.customer_id,
       concat(u.first_name, ' ', u.last_name, ' (', u.login, ')') as user, concat(ru.first_name, ' ', ru.last_name, ' (', ru.login, ')') as ruser,
       q.name as qname, tp.name as tpname, ts.name as tsname, tlp.name as tlpname
FROM ticket as tk, users as u, users as ru, queue as q, ticket_priority as tp, ticket_state as ts, ticket_lock_type as tlp
WHERE u.id   = tk.user_id
  AND ru.id  = tk.responsible_user_id
  AND q.id   = tk.queue_id
  AND tp.id  = tk.ticket_priority_id
  AND ts.id  = tk.ticket_state_id
  AND tlp.id = tk.ticket_lock_id
"""
// Add these to the SQL part for sort by change time or limit import for testing purposes
// order by tk.change_time desc
// LIMIT 20000

    ticket.eachRow(ticket_sql) { tk ->

        tkNum++
        log.warn("""Row processed: ${tkNum}""")
        Issue issue

        def jqlReportIssues     = """ project = OTRS and "OTRS Ticket Number" ~ "${tk.getString("tn")}" """
		log.warn(jqlReportIssues)
		def queryReportIssues   = jqlQueryParser.parseQuery(jqlReportIssues)
		def resultsReportIssues = searchProvider.search(queryReportIssues, user, new PagerFilter(10))

        if ( ! resultsReportIssues.getIssues() ) {

            tpNum++
            def tkTitle = tk.getString("title")
            if ( tk.getString("title") == "" ) {
                tkTitle = "No Summary"
            }
            def tkDetails = """
|Ticket:      |${tk.getString("tn")} |
|Title:       |${tkTitle} |
|State:       |${tk.getString("tsname")} |
|Locked:      |${tk.getString("tlpname")} |
|Priority:    |${tk.getString("tpname")} |
|Queue:       |${tk.getString("qname")} |
|CustomerID:  |${tk.getString("customer_id")} |
|Owner:       |${tk.getString("user")} |
|Responsible: |${tk.getString("ruser")} |
|Created:     |${tk.getTimestamp("create_time")} |
|URL:         |[https://otrs-ui/index.pl?Action=AgentTicketZoom;TicketID=${tk.getLong("id")}]| 
"""
            //log.warn(tkDetails)
            log.warn("${tk.getString("tn")} - https://otrs-ui/index.pl?Action=AgentTicketZoom;TicketID=${tk.getLong("id")}")
            log.warn("""Issue created this round: ${tpNum}""")


            def article_sql = """SELECT at.name as type, a.id, a.a_from, a.a_reply_to, a.a_to, a.a_cc, a_subject, a.a_body, a.create_time
FROM article as a, article_type as at
WHERE a.ticket_id = ${tk.getLong("id")}
  and at.id = a.article_type_id
order by a.id"""

            def artNum = 0
            def tkDescription = """"""
            def tkComments = []
            def tkAttachments = []

            article.eachRow(article_sql) { a ->
                artNum++

                // Attachments
                def article_att_sql = """SELECT * FROM article_attachment WHERE article_id = ${a.getLong("id")}
"""
                def attAttachments = []

                article_att.eachRow(article_att_sql) { att ->
                    def att_details = new LinkedHashMap()

                    def fileName = FilenameUtils.getName(att.getString("filename"))

                    att_details.put("filename", """(${att.getLong("article_id")}-${att.getLong("id")}) ${fileName}""")
                    att_details.put("content_type", """${att.getString("content_type")}""")

                    File tempFile
                    tempFile = new File("/tmp/${att.getLong("article_id")}-${att.getLong("id")}-${fileName}")
                    FileCopyUtils.copy(att.getBytes("content"), tempFile)

                    att_details.put("filepath", """/tmp/${att.getLong("article_id")}-${att.getLong("id")}-${fileName}""")

                    attAttachments.add("""[^(${att.getLong("article_id")}-${att.getLong("id")}) ${fileName}]""")

                    tkAttachments.add(att_details)
                }

                if ( artNum == 1 ) {
                    tkDescription = """
|Type:        |${a.getString("type")} |
|From:        |${a.getString("a_from")} |
|To:          |${a.getString("a_to")} |
|Reply To:    |${a.getString("a_reply_to")} |
|CC:          |${a.getString("a_cc")} |
|Subject:     |${a.getString("a_subject")} |
|Created:     |${a.getTimestamp("create_time")} |
|Attachments: |${attAttachments.join("\n")} |
|ID:          |${a.getLong("id")} |

   

${a.getString("a_body").replaceAll("${(char)0}", "")}
"""
                } else {
                    tkComments.add("""
|Type:        |${a.getString("type")} |
|From:        |${a.getString("a_from")} |
|To:          |${a.getString("a_to")} |
|Reply To:    |${a.getString("a_reply_to")} |
|CC:          |${a.getString("a_cc")} |
|Subject:     |${a.getString("a_subject")} |
|Created:     |${a.getTimestamp("create_time")} |
|Attachments: |${attAttachments.join("\n")} |
|ID:          |${a.getLong("id")} |

   

${a.getString("a_body").replaceAll("${(char)0}", "")}
""")
                }
            }

            def ticket_history_sql = """SELECT tht.name as ACTION, th.name as COMMENT, concat(u.login, ' (', u.first_name, ' ', u.last_name, ')') as USER, th.create_time as CREATETIME
FROM ticket_history as th, ticket_history_type as tht, users as u
WHERE th.ticket_id = ${tk.getLong("id")}
  and tht.id = th.history_type_id
  and u.id = th.create_by"""

            def tkHistory = """||ACTION||COMMENT||USER||CREATETIME||\n"""
            ticket_history.eachRow(ticket_history_sql) { th ->
                //log.warn(th.getString("ACTION"))
                //log.warn(th.getString("COMMENT").replaceFirst("%%", ""))
                def thComment = sprintf(history_format.get(th.getString("ACTION")).toString(), th.getString("COMMENT").replaceFirst("%%", "").split("%%") )
                tkHistory = tkHistory + """|${th.getString("ACTION")}|${thComment}|${th.getString("USER")}|${th.getTimestamp("CREATETIME")}|\n"""
            }

            // Some logging to check import data
            //log.warn(tkDescription)
            //log.warn(tkComments)
            //log.warn(tkHistory)

            // Creating Issue
            def issueInputParameters = issueService.newIssueInputParameters()
            issueInputParameters.setProjectId(projectid)
                .setSummary(tkTitle)
                .setIssueTypeId("10000")
                .setReporterId(user.key)
                .setDescription(tkDescription.toString())
                .addCustomFieldValue(<OTRS Ticket Number CFID>, tk.getString("tn"))																					// OTRS Ticket Number
                .addCustomFieldValue(<OTRS Ticket History CFID>, tkHistory)                                                											// OTRS Ticket History
                .addCustomFieldValue(<OTRS Ticket Details CFID>, tkDetails)																							// OTRS Ticket Details
                .addCustomFieldValue(<OTRS Ticket Created CFID>, tk.getTimestamp("create_time").format("dd/MMM/yyyy hh:mm a"))										// OTRS Ticket Created
                .addCustomFieldValue(<OTRS Ticket URL CFID>, """https://otrs-ui/index.pl?Action=AgentTicketZoom;TicketID=${tk.getLong("id")}""")		// OTRS Ticket URL
                .setSkipScreenCheck(true)

            //log.warn("Input params: ${issueInputParameters.toString()}")

            def createValidationResult = issueService.validateCreate(user, issueInputParameters)

            //log.warn("Validation?: ${createValidationResult.isValid().toString()}")

            if (createValidationResult.isValid()) {
                def createResult = issueService.create(user, createValidationResult);
                //log.warn("Creation result?: ${createResult.isValid().toString()}")

                if ( createResult.isValid() ) {
                    log.warn(createResult.getIssue().getKey())
                    tkComments.forEach() { tkComment ->
                        //log.warn(tkComment)
                        commentManager.create(createResult.getIssue(), user, tkComment.toString(), false)
                        sleep(2)
                    }
                    tkAttachments.forEach() { tkAttachment ->
                        //log.warn(((LinkedHashMap) tkAttachment).get("filepath"))
                        //log.warn(((LinkedHashMap) tkAttachment).get("filename"))
                        //log.warn(((LinkedHashMap) tkAttachment).get("contenttype"))
                        File attFile
                        attFile = new File( (String) ((LinkedHashMap) tkAttachment).get("filepath") )
                        def bean = new CreateAttachmentParamsBean.Builder()
                            .file( attFile)
                            .filename( (String) ((LinkedHashMap) tkAttachment).get("filename") )
                            .contentType( (String) ((LinkedHashMap) tkAttachment).get("contenttype") )
                            .author(user)
                            .issue(createResult.getIssue())
                            .build()

                        attachmentManager.createAttachment(bean)

                        // removing temporary file
                        attFile.delete()
                    }
                    Thread.sleep(50)
                } else {
                    log.warn("Something went wrong with issue creation")
                    log.warn(createResult.getErrorCollection().getErrors())
                }
            } else {
                log.warn("Something went wrong with issue validation")
                log.warn(createValidationResult.getErrorCollection().getErrors())
            }
        } else {
            log.warn("Jira issue ${resultsReportIssues.getIssues().get(0).getKey()} with ticket number ${tk.getString("tn")} already exist - ignoring")
        }
    }
    def stopTime = LocalDateTime.now()
    log.warn(startTime)
    log.warn(stopTime)
    log.warn("Rows processed: ${tkNum}")
    log.warn("Issues created this round: ${tpNum}")
} finally {
    ticket.close()
	ticket_history.close()
	article.close()
	article_att.close()
    conn.close()
}
