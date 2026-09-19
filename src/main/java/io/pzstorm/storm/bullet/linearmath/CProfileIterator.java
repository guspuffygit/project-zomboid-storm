// Port of LinearMath/btQuickprof.h / .cpp, class CProfileIterator (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Iterator over the profile tree. {@code Enter_Largest_Child} is declared in 2.82 but never
 * defined, so it is not ported.
 */
public class CProfileIterator {
    protected CProfileNode CurrentParent;
    protected CProfileNode CurrentChild;

    protected CProfileIterator(CProfileNode start) {
        CurrentParent = start;
        CurrentChild = CurrentParent.Get_Child();
    }

    public void First() {
        CurrentChild = CurrentParent.Get_Child();
    }

    public void Next() {
        CurrentChild = CurrentChild.Get_Sibling();
    }

    public boolean Is_Done() {
        return CurrentChild == null;
    }

    public boolean Is_Root() {
        return (CurrentParent.Get_Parent() == null);
    }

    public void Enter_Child(int index) {
        CurrentChild = CurrentParent.Get_Child();
        while ((CurrentChild != null) && (index != 0)) {
            index--;
            CurrentChild = CurrentChild.Get_Sibling();
        }
        if (CurrentChild != null) {
            CurrentParent = CurrentChild;
            CurrentChild = CurrentParent.Get_Child();
        }
    }

    public void Enter_Parent() {
        if (CurrentParent.Get_Parent() != null) {
            CurrentParent = CurrentParent.Get_Parent();
        }
        CurrentChild = CurrentParent.Get_Child();
    }

    public String Get_Current_Name() {
        return CurrentChild.Get_Name();
    }

    public int Get_Current_Total_Calls() {
        return CurrentChild.Get_Total_Calls();
    }

    public float Get_Current_Total_Time() {
        return CurrentChild.Get_Total_Time();
    }

    public Object Get_Current_UserPointer() {
        return CurrentChild.GetUserPointer();
    }

    public void Set_Current_UserPointer(Object ptr) {
        CurrentChild.SetUserPointer(ptr);
    }

    public String Get_Current_Parent_Name() {
        return CurrentParent.Get_Name();
    }

    public int Get_Current_Parent_Total_Calls() {
        return CurrentParent.Get_Total_Calls();
    }

    public float Get_Current_Parent_Total_Time() {
        return CurrentParent.Get_Total_Time();
    }
}
