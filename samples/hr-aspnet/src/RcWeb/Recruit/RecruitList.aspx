<%@ Page Title="채용공고 목록" Language="C#" MasterPageFile="~/Site.Master" AutoEventWireup="true"
    CodeBehind="RecruitList.aspx.cs" Inherits="RcWeb.Recruit.RecruitList" %>

<asp:Content ID="Content1" ContentPlaceHolderID="MainContent" runat="server">
    <h2>채용공고 목록</h2>
    <table class="search">
        <tr>
            <th><asp:Label ID="lblTitle" runat="server" AssociatedControlID="txtTitle" Text="공고명" /></th>
            <td><asp:TextBox ID="txtTitle" runat="server" ClientIDMode="Static" /></td>
            <th>상태</th>
            <td>
                <asp:DropDownList ID="ddlStatus" runat="server" ClientIDMode="Static" AutoPostBack="true"
                    OnSelectedIndexChanged="ddlStatus_SelectedIndexChanged">
                    <asp:ListItem Value="" Text="전체" />
                    <asp:ListItem Value="OPEN" Text="진행" />
                    <asp:ListItem Value="CLOSE" Text="마감" />
                </asp:DropDownList>
            </td>
            <td><asp:Button ID="btnSearch" runat="server" Text="조회" OnClick="btnSearch_Click" ClientIDMode="Static" /></td>
        </tr>
    </table>

    <span>공고 수: <span id="spnCount"></span></span>
    <input type="button" id="btnCount" value="건수 새로고침" />

    <asp:GridView ID="gvRecruit" runat="server" AutoGenerateColumns="false" DataKeyNames="RECRUIT_NO" ClientIDMode="Static"
        OnRowCommand="gvRecruit_RowCommand">
        <Columns>
            <asp:BoundField DataField="RECRUIT_NO" HeaderText="공고번호" />
            <asp:BoundField DataField="TITLE" HeaderText="공고명" />
            <asp:BoundField DataField="DEPT_NM" HeaderText="부서" />
            <asp:BoundField DataField="STATUS" HeaderText="상태" />
            <asp:ButtonField CommandName="EditRow" Text="수정" />
        </Columns>
    </asp:GridView>

    <div class="buttons">
        <asp:Button ID="btnNew" runat="server" Text="신규" OnClick="btnNew_Click" ClientIDMode="Static" />
        <asp:Button ID="btnClose" runat="server" Text="마감처리" OnClick="btnClose_Click" ClientIDMode="Static"
            OnClientClick="return confirm('선택한 공고를 마감하시겠습니까?');" />
        <asp:Button ID="btnExcel" runat="server" Text="엑셀" ClientIDMode="Static" />
    </div>

    <script src="<%= ResolveUrl("~/Scripts/rc/recruit.js") %>"></script>
</asp:Content>
