package com.bookrio.shared.reader

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIPageViewController
import platform.UIKit.UIPageViewControllerDataSourceProtocol
import platform.UIKit.UIPageViewControllerDelegateProtocol
import platform.UIKit.UIPageViewControllerNavigationDirection
import platform.UIKit.UIPageViewControllerNavigationOrientationHorizontal
import platform.UIKit.UIPageViewControllerTransitionStylePageCurl
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * Native iOS PDF reader: `UIPageViewController` with the built-in `.pageCurl`
 * transition, one PDFKit-rendered page per child view controller.
 *
 * Apple's page-curl transition is used on purpose instead of porting the Android
 * `:pagecurl` custom canvas — see HANDOFF.md / KMP_PORT_STATUS.md.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class PdfPageCurlReader(
    document: PDFDocument,
    startPage: Int,
    private val onPageChanged: (page: Int, totalPages: Int) -> Unit,
) : UIPageViewController(
    UIPageViewControllerTransitionStylePageCurl,
    UIPageViewControllerNavigationOrientationHorizontal,
    null,
) {

    val pageCount: Int = document.pageCount.toInt()
    private var currentIndex: Int = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    // Weak self keeps the data source/delegate from forming a retain cycle with
    // the controller (they are owned by it, and ObjC holds dataSource/delegate weakly).
    private val selfRef = WeakReference(this)
    private val pageFactory = PageFactory(document)
    private val pageSource = PageSource(pageCount) { index -> pageFactory.create(index) }
    private val pageDelegate = PageDelegate { controller -> selfRef.value?.onSettled(controller) }
    private val closeButton = UIButton(CGRectMake(16.0, 52.0, 92.0, 40.0))

    init {
        dataSource = pageSource
        delegate = pageDelegate
        if (pageCount > 0) {
            setViewControllers(
                viewControllers = listOf(pageFactory.create(currentIndex)),
                direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
                animated = false,
                completion = null,
            )
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        closeButton.setTitle("Lukk", UIControlStateNormal)
        closeButton.setTitleColor(UIColor.whiteColor, UIControlStateNormal)
        val closeAction = UIAction.actionWithHandler { selfRef.value?.closeReader() }
        closeButton.addAction(closeAction, UIControlEventTouchUpInside)
        view.addSubview(closeButton)
        view.bringSubviewToFront(closeButton)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // UIPageViewController adds each page's view above ours on every turn, so
        // re-assert the close button on top after layout.
        view.bringSubviewToFront(closeButton)
    }

    private fun closeReader() {
        dismissViewControllerAnimated(true, null)
    }

    private fun onSettled(controller: PdfPageViewController) {
        currentIndex = controller.pageIndex
        onPageChanged(currentIndex, pageCount)
    }
}

/**
 * Renders (and lazily caches) one `UIImage` per PDF page. The cache is bounded so
 * flipping through a long PDF cannot retain every page bitmap in memory.
 */
@OptIn(ExperimentalForeignApi::class)
private class PageFactory(private val document: PDFDocument) {

    private val cache = HashMap<Int, UIImage?>()
    private val insertionOrder = ArrayDeque<Int>()
    private val maxCachedPages = 12

    fun create(index: Int): PdfPageViewController {
        val image = cache[index] ?: renderPage(index).also { rendered ->
            cache[index] = rendered
            insertionOrder.addLast(index)
            if (insertionOrder.size > maxCachedPages) {
                cache.remove(insertionOrder.removeFirst())
            }
        }
        return PdfPageViewController(index, image)
    }

    private fun renderPage(index: Int): UIImage? {
        val page = document.pageAtIndex(index.toULong()) ?: return null
        val box = kPDFDisplayBoxMediaBox
        val bounds = page.boundsForBox(box)
        // Render at 2x so the curl has enough resolution on Retina displays.
        val width = CGRectGetWidth(bounds) * 2.0
        val height = CGRectGetHeight(bounds) * 2.0
        return page.thumbnailOfSize(CGSizeMake(width, height), box)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PageSource(
    private val pageCount: Int,
    private val makePage: (Int) -> PdfPageViewController,
) : NSObject(), UIPageViewControllerDataSourceProtocol {

    @ObjCSignatureOverride
    override fun pageViewController(
        pageViewController: UIPageViewController,
        viewControllerBeforeViewController: UIViewController,
    ): UIViewController? {
        val index = (viewControllerBeforeViewController as? PdfPageViewController)?.pageIndex ?: return null
        return if (index > 0) makePage(index - 1) else null
    }

    @ObjCSignatureOverride
    override fun pageViewController(
        pageViewController: UIPageViewController,
        viewControllerAfterViewController: UIViewController,
    ): UIViewController? {
        val index = (viewControllerAfterViewController as? PdfPageViewController)?.pageIndex ?: return null
        return if (index < pageCount - 1) makePage(index + 1) else null
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PageDelegate(
    private val onSettled: (PdfPageViewController) -> Unit,
) : NSObject(), UIPageViewControllerDelegateProtocol {

    override fun pageViewController(
        pageViewController: UIPageViewController,
        didFinishAnimating: Boolean,
        previousViewControllers: List<*>,
        transitionCompleted: Boolean,
    ) {
        if (!transitionCompleted) return
        val controller = pageViewController.viewControllers?.firstOrNull() as? PdfPageViewController ?: return
        onSettled(controller)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PdfPageViewController(
    val pageIndex: Int,
    private val image: UIImage?,
) : UIViewController(null, null) {

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        val imageView = UIImageView(image)
        imageView.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
        imageView.setFrame(view.bounds)
        imageView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        view.addSubview(imageView)
    }
}