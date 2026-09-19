// Port of LinearMath/btQuickprof.h / .cpp, class CProfileNode (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * A node in the profile hierarchy tree. {@code const char* name} is a {@link String}; {@link
 * #Get_Sub_Node} compares names by reference ({@code ==}) exactly like the C++ pointer compare, so
 * callers should pass constant (interned) strings. New children are prepended to the child list.
 * {@code float} fields stay {@code float}.
 */
public class CProfileNode {
    protected String Name;
    protected int TotalCalls;
    protected float TotalTime;
    protected long StartTime; // unsigned long
    protected int RecursionCounter;
    protected CProfileNode Parent;
    protected CProfileNode Child;
    protected CProfileNode Sibling;
    protected Object m_userPtr;

    public CProfileNode(String name, CProfileNode parent) {
        Name = name;
        TotalCalls = 0;
        TotalTime = 0;
        StartTime = 0;
        RecursionCounter = 0;
        Parent = parent;
        Child = null;
        Sibling = null;
        m_userPtr = null;
        Reset();
    }

    @SuppressWarnings("StringEquality")
    public CProfileNode Get_Sub_Node(String name) {
        CProfileNode child = Child;
        while (child != null) {
            if (child.Name == name) {
                return child;
            }
            child = child.Sibling;
        }
        CProfileNode node = new CProfileNode(name, this);
        node.Sibling = Child;
        Child = node;
        return node;
    }

    public CProfileNode Get_Parent() {
        return Parent;
    }

    public CProfileNode Get_Sibling() {
        return Sibling;
    }

    public CProfileNode Get_Child() {
        return Child;
    }

    /** {@code delete Child; Child = NULL; delete Sibling; Sibling = NULL;} */
    public void CleanupMemory() {
        Child = null;
        Sibling = null;
    }

    public void Reset() {
        TotalCalls = 0;
        TotalTime = 0.0f;
        if (Child != null) {
            Child.Reset();
        }
        if (Sibling != null) {
            Sibling.Reset();
        }
    }

    public void Call() {
        TotalCalls++;
        if (RecursionCounter++ == 0) {
            StartTime = CProfileManager.Profile_Get_Ticks();
        }
    }

    public boolean Return() {
        if (--RecursionCounter == 0 && TotalCalls != 0) {
            long time = CProfileManager.Profile_Get_Ticks();
            time -= StartTime;
            TotalTime += (float) time / CProfileManager.Profile_Get_Tick_Rate();
        }
        return (RecursionCounter == 0);
    }

    public String Get_Name() {
        return Name;
    }

    public int Get_Total_Calls() {
        return TotalCalls;
    }

    public float Get_Total_Time() {
        return TotalTime;
    }

    public Object GetUserPointer() {
        return m_userPtr;
    }

    public void SetUserPointer(Object ptr) {
        m_userPtr = ptr;
    }
}
