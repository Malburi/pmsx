<%@ Page Title="채용공고 등록" Language="C#" MasterPageFile="~/Site.Master" AutoEventWireup="true"
    CodeBehind="RecruitEdit.aspx.cs" Inherits="RcWeb.Recruit.RecruitEdit" %>

<asp:Content ID="Content1" ContentPlaceHolderID="MainContent" runat="server">
    <h2>채용공고 등록</h2>
    <table class="form">
        <tr>
            <th><asp:Label ID="lblTitle" runat="server" AssociatedControlID="txtTitle" Text="공고명" /></th>
            <td><asp:TextBox ID="txtTitle" runat="server" ClientIDMode="Static" /></td>
        </tr>
        <tr>
            <th><asp:Label ID="lblDept" runat="server" AssociatedControlID="ddlDept" Text="부서" /></th>
            <td><asp:DropDownList ID="ddlDept" runat="server" ClientIDMode="Static" /></td>
        </tr>
        <tr>
            <th><asp:Label ID="lblEndDt" runat="server" AssociatedControlID="txtEndDt" Text="마감일" /></th>
            <td><asp:TextBox ID="txtEndDt" runat="server" TextMode="Date" ClientIDMode="Static" /></td>
        </tr>
        <tr>
            <th><asp:Label ID="lblContent" runat="server" AssociatedControlID="txtContent" Text="내용" /></th>
            <td><asp:TextBox ID="txtContent" runat="server" TextMode="MultiLine" Rows="8" ClientIDMode="Static" /></td>
        </tr>
    </table>
    <asp:HiddenField ID="hdnNo" runat="server" />
    <div class="buttons">
        <asp:Button ID="btnSave" runat="server" Text="저장" OnClick="btnSave_Click" ClientIDMode="Static"
            OnClientClick="return confirm('저장하시겠습니까?');" />
        <asp:LinkButton ID="lnkList" runat="server" OnClick="lnkList_Click" ClientIDMode="Static">목록</asp:LinkButton>
    </div>
</asp:Content>
