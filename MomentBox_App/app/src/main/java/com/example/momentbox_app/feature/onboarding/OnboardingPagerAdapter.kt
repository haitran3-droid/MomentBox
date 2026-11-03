package com.example.momentbox_app.feature.onboarding
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import com.ntq.training.momentbox.R
class OnboardingPagerAdapter(private val context: Context) : PagerAdapter() {

    /** Danh sách 3 layout XML độc lập cho từng trang Onboarding */
    private val layouts = intArrayOf(
        R.layout.item_onboarding_page1,
        R.layout.item_onboarding_page2,
        R.layout.item_onboarding_page3
    )

    override fun getCount(): Int = layouts.size

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view == `object`
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val inflater = LayoutInflater.from(context)
        val layout = layouts[position]
        val view = inflater.inflate(layout, container, false)
        container.addView(view)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        if (`object` is View) {
            container.removeView(`object`)
        }
    }
}