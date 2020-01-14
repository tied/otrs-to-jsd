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

// Issue Service
def issueService      = ComponentAccessor.getIssueService()
// Comment Manager
def commentManager    = ComponentAccessor.getCommentManager()
//  Attachment Manager
def attachmentManager = ComponentAccessor.getAttachmentManager()
// Link Manager
def issueLinkManage   = ComponentAccessor.getIssueLinkManager()
// Project ID and Admin User
def projectid         = ComponentAccessor.getProjectManager().getProjectByCurrentKey("OTRS").getId()
def user              = ComponentAccessor.getUserManager().getUserByKey("admin")

// Search Service
def jqlQueryParser    = ComponentAccessor.getComponent(JqlQueryParser.class)
def searchProvider    = ComponentAccessor.getComponent(SearchProvider.class)

def driver = Class.forName('com.mysql.jdbc.Driver').newInstance() as Driver 

def props = new Properties()
props.setProperty("user",     "otrs_ro") 
props.setProperty("password", "otrs")

def conn = driver.connect("jdbc:mysql://otrs-mysql:3306/otrs", props) 
def links          = new Sql(conn)

try {
    //log.warn("start")
    def startTime = LocalDateTime.now()
    def lrNum = 0
    def links_sql = """SELECT tsrc.tn as src_tn, tdst.tn as dst_tn, lr.type_id
FROM link_relation as lr, ticket as tsrc, ticket as tdst
where tsrc.id = lr.source_key
  AND tdst.id = lr.target_key
"""
    links.eachRow(links_sql) { lr ->

        lrNum++
        log.warn(lrNum)

        def jqlSrcIssue     = """ project = OTRS and "OTRS Ticket Number" ~ "${lr.getString("src_tn")}" """
		log.warn(jqlSrcIssue)
		def querySrcIssue   = jqlQueryParser.parseQuery(jqlSrcIssue)
		def resultsSrcIssue = searchProvider.search(querySrcIssue, user, new PagerFilter(10))

        def jqlDstIssue     = """ project = OTRS and "OTRS Ticket Number" ~ "${lr.getString("dst_tn")}" """
		log.warn(jqlDstIssue)
		def queryDstIssue   = jqlQueryParser.parseQuery(jqlDstIssue)
		def resultsDstIssue = searchProvider.search(queryDstIssue, user, new PagerFilter(10))

        if ( resultsSrcIssue.getIssues() && resultsDstIssue.getIssues() ) {

            if ( lr.getString("type_id") == "1" ) {
                issueLinkManage.createIssueLink(resultsSrcIssue.getIssues().get(0).getId(), resultsDstIssue.getIssues().get(0).getId(), <Related to ID>, 1, user)
                Thread.sleep(50)
                log.warn("""Linking ${resultsSrcIssue.getIssues().get(0).getKey()} to ${resultsDstIssue.getIssues().get(0).getKey()} as Related""")
            } else if ( lr.getString("type_id") == "2" ) {
                issueLinkManage.createIssueLink(resultsSrcIssue.getIssues().get(0).getId(), resultsDstIssue.getIssues().get(0).getId(), <Parent of ID>, 1, user)
                Thread.sleep(50)
                log.warn("""Linking ${resultsSrcIssue.getIssues().get(0).getKey()} to ${resultsDstIssue.getIssues().get(0).getKey()} as Parent""")
            }
        }

    }
    def stopTime = LocalDateTime.now()
    log.warn(startTime)
    log.warn(stopTime)
    log.warn("Rows: ${lrNum}")
} finally {
    links.close()
    conn.close()
}
