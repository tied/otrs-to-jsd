import groovy.sql.Sql
import java.sql.Driver
import com.atlassian.jira.component.ComponentAccessor
import org.springframework.util.FileCopyUtils
import com.atlassian.jira.issue.attachment.CreateAttachmentParamsBean
import java.time.LocalDateTime
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.issue.search.SearchProvider
import com.atlassian.jira.issue.fields.CustomField
import org.apache.commons.io.FilenameUtils
import com.atlassian.jira.event.type.EventDispatchOption

// Custom Field Manager
def cfManager         = ComponentAccessor.getCustomFieldManager()
// Issue Service
def issueService      = ComponentAccessor.getIssueService()
// Issue Manager
def issueManager      = ComponentAccessor.getIssueManager()
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

// OTRS Ticket Check CF
def cfOTRSTicketCheck = cfManager.getCustomFieldObjectByName( "OTRS Ticket Check" )


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


// Mark Issues - "OTRS Ticket Check" field
def chkMark = false // change to true if required

def driver = Class.forName('com.mysql.jdbc.Driver').newInstance() as Driver 

def props = new Properties()
props.setProperty("user",     "otrs_ro") 
props.setProperty("password", "otrs")

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
    def tbNum = 0
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
// order by tk.change_time desc
// LIMIT 20000

    ticket.eachRow(ticket_sql) { tk ->

        tkNum++
        log.warn("""Row processed: ${tkNum}""")
        Issue issue

        def jqlReportIssues     = """ project = OTRS and "OTRS Ticket Number" ~ "${tk.getString("tn")}" """
		//log.warn(jqlReportIssues)
		def queryReportIssues   = jqlQueryParser.parseQuery(jqlReportIssues)
		def resultsReportIssues = searchProvider.search(queryReportIssues, user, new PagerFilter(10))

        if ( resultsReportIssues.getIssues().size() == 1 ) {

            tpNum++
            def checkIssue = resultsReportIssues.getIssues().get(0)
            //log.warn(checkIssue.getKey())
            //log.warn("${tk.getString("tn")} - https://otrs-ui/index.pl?Action=AgentTicketZoom;TicketID=${tk.getLong("id")}")
            //log.warn("""Issue checked: ${tpNum}""")


            def article_sql = """SELECT at.name as type, a.id, a.a_from, a.a_reply_to, a.a_to, a.a_cc, a_subject, a.a_body, a.create_time
FROM article as a, article_type as at
WHERE a.ticket_id = ${tk.getLong("id")}
  and at.id = a.article_type_id
order by a.id"""

            def artNum = article.rows(article_sql).size() - 1
            def attNum = 0

            article.eachRow(article_sql) { a ->
                // Attachments
                def article_att_sql = """SELECT * FROM article_attachment WHERE article_id = ${a.getLong("id")}
"""
                attNum = attNum + article_att.rows(article_att_sql).size()
            }

            def checkIssueComments = commentManager.getComments(checkIssue).size()
            def checkIssueAttachments = checkIssue.getAttachments().size()

            MutableIssue curIssue = issueManager.getIssueObject(checkIssue.getId())
            if ( checkIssueComments != artNum  || checkIssueAttachments != attNum ) {
                tbNum++
                log.warn("""Ticket "${tk.getString("tn")}" and Jira ${checkIssue.getKey()} has differences """)
                log.warn("""   Comments:    T:${artNum} J:${checkIssueComments} """)
                log.warn("""   Attachments: T:${attNum} J:${checkIssueAttachments} """)
                if ( chkMark ) {
                    curIssue.setCustomFieldValue(cfOTRSTicketCheck, (double) 20)
                    issueManager.updateIssue(user, curIssue, EventDispatchOption.ISSUE_UPDATED, false)
                }
            } else {
				if ( chkMark ) {
                    curIssue.setCustomFieldValue(cfOTRSTicketCheck, (double) 10)
                    issueManager.updateIssue(user, curIssue, EventDispatchOption.ISSUE_UPDATED, false)
                }
            }

        } else if ( resultsReportIssues.getIssues().size() > 1 ) {
            log.warn("""More than 1 Jira issues with CF "OTRS Ticket Number" with value "${tk.getString("tn")}" is in archive, one needs to be removed """)
            if ( chkMark ) {
                def i = 0
                for(i=0;i<resultsReportIssues.getIssues().size();i++) {
                    MutableIssue curIssue = issueManager.getIssueObject(resultsReportIssues.getIssues().get(i).getId())
                    curIssue.setCustomFieldValue(cfOTRSTicketCheck, (double) 30)
                    issueManager.updateIssue(user, curIssue, EventDispatchOption.ISSUE_UPDATED, false)
                }
            }
        } else {
            log.warn("""No Jira issue is in archive with CF "OTRS Ticket Number" value of "${tk.getString("tn")}" """)
        }
    }
    def stopTime = LocalDateTime.now()
    log.warn(startTime)
    log.warn(stopTime)
    log.warn("Rows processed:  ${tkNum}")
    log.warn("Issues checked:  ${tpNum}")
    log.warn("Issues detected: ${tbNum}")
} finally {
    ticket.close()
	ticket_history.close()
	article.close()
	article_att.close()
    conn.close()
}
