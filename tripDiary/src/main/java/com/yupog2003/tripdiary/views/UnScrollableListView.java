package com.yupog2003.tripdiary.views;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ListView;

public class UnScrollableListView extends ListView{
	public UnScrollableListView(Context context) {
		super(context);

	}
	public UnScrollableListView(Context context, AttributeSet attrs) {
		super(context, attrs);

	}
	public UnScrollableListView(Context context, AttributeSet attrs,
			int defStyle) {
		super(context, attrs, defStyle);

	}
	
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

		int heightSpec=MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE>>2, MeasureSpec.AT_MOST);
		super.onMeasure(widthMeasureSpec, heightSpec);
		getLayoutParams().height=getMeasuredHeight();
	}
	

}
